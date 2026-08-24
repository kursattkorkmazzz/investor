package com.investor.ontology.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import com.investor.ontology.OntologyException;
import com.investor.ontology.OntologySnapshot;
import com.investor.ontology.OntologyStore;
import com.investor.ontology.model.ActorType;
import com.investor.ontology.model.Cardinality;
import com.investor.ontology.model.DataType;
import com.investor.ontology.model.CommitContext;
import com.investor.ontology.model.LinkProperties;
import com.investor.ontology.model.LinkTypeDef;
import com.investor.ontology.model.LinkView;
import com.investor.ontology.model.NewLinkType;
import com.investor.ontology.model.NewObjectType;
import com.investor.ontology.model.NewPropertyType;
import com.investor.ontology.model.ObjectRef;
import com.investor.ontology.model.ObjectTypeDef;
import com.investor.ontology.model.ObjectView;
import com.investor.ontology.model.PropertyHistoryEntry;
import com.investor.ontology.model.PropertyTypeDef;
import com.investor.ontology.model.Value;
import com.investor.ontology.query.OntologyQuery;
import com.investor.ontology.query.QueryResult;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.investor.ontology.internal.SqlSupport.instant;
import static com.investor.ontology.internal.SqlSupport.translate;
import static com.investor.ontology.internal.SqlSupport.ts;
import static com.investor.ontology.internal.SqlSupport.uuid;

/**
 * {@link OntologyStore}'un JDBC gerçeklemesi.
 *
 * <h2>Yazma disiplini</h2>
 * Hiçbir değer kolonu UPDATE ile ezilmez. Yapılan tek UPDATE, bir geçerlilik aralığını
 * kapatan {@code valid_to} ve bir kaydı geri çeken {@code retracted_at} damgasıdır;
 * ikisi de bilgi eklemek içindir, silmek için değil.
 */
class JdbcOntologyStore implements OntologyStore {

    private final JdbcClient jdbc;
    private final SchemaRegistry registry;
    private final ValueCodec codec;
    private final QueryCompiler queryCompiler;
    private final Clock clock;

    JdbcOntologyStore(JdbcClient jdbc, SchemaRegistry registry, ValueCodec codec,
                      QueryCompiler queryCompiler, Clock clock) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.codec = codec;
        this.queryCompiler = queryCompiler;
        this.clock = clock;
    }

    // =====================================================================
    // META
    // =====================================================================

    @Override
    @Transactional
    public ObjectTypeDef defineObjectType(NewObjectType spec, CommitContext ctx) {
        UUID commitId = resolveCommit(ctx);
        UUID parentId = spec.parentTypeApiName() == null
                ? null
                : registry.requireType(spec.parentTypeApiName()).id();

        UUID id;
        try {
            id = jdbc.sql("""
                    INSERT INTO object_type (api_name, display_name, description, icon,
                                             is_abstract, parent_type_id, created_by)
                    VALUES (:apiName, :displayName, :description, :icon,
                            :isAbstract, :parentId, :createdBy)
                    RETURNING id
                    """)
                    .param("apiName", spec.apiName())
                    .param("displayName", spec.displayName())
                    .param("description", spec.description())
                    .param("icon", spec.icon())
                    .param("isAbstract", spec.isAbstract())
                    .param("parentId", parentId)
                    .param("createdBy", ctx.actorId())
                    .query(UUID.class)
                    .single();
        } catch (DataIntegrityViolationException e) {
            throw translate(e, "Tip oluşturulamadı: " + spec.apiName());
        }

        writeTypeVersion(id, 1, "İlk sürüm", ctx);
        log(commitId, "CREATE_TYPE", null, null, null, null,
                Map.of("apiName", spec.apiName(), "displayName", spec.displayName()));
        registry.reload();
        return registry.requireType(spec.apiName());
    }

    @Override
    @Transactional
    public PropertyTypeDef defineProperty(String typeApiName, NewPropertyType spec, CommitContext ctx) {
        UUID commitId = resolveCommit(ctx);
        ObjectTypeDef type = registry.requireType(typeApiName);

        if (spec.isRequired() && objectCount(type.id()) > 0) {
            throw new OntologyException.SchemaViolation(
                    ("'%s' tipinde zaten nesne var; zorunlu alan doğrudan eklenemez. "
                            + "Önce opsiyonel ekleyin, veriyi doldurun, sonra zorunlu yapın.")
                            .formatted(typeApiName));
        }

        int nextVersion = type.currentVersion() + 1;
        UUID id;
        try {
            id = jdbc.sql("""
                    INSERT INTO property_type (object_type_id, api_name, display_name, description,
                                               data_type, cardinality, is_required, is_title, unit,
                                               constraints, introduced_in_version, display_order)
                    VALUES (:typeId, :apiName, :displayName, :description,
                            :dataType, :cardinality, :isRequired, :isTitle, :unit,
                            CAST(:constraints AS jsonb), :version, :displayOrder)
                    RETURNING id
                    """)
                    .param("typeId", type.id())
                    .param("apiName", spec.apiName())
                    .param("displayName", spec.displayName())
                    .param("description", spec.description())
                    .param("dataType", spec.dataType().name())
                    .param("cardinality", spec.cardinality().name())
                    .param("isRequired", spec.isRequired())
                    .param("isTitle", spec.isTitle())
                    .param("unit", spec.unit())
                    .param("constraints", codec.writeJson(spec.constraints()))
                    .param("version", nextVersion)
                    .param("displayOrder", spec.displayOrder())
                    .query(UUID.class)
                    .single();
        } catch (DataIntegrityViolationException e) {
            throw translate(e, "Alan eklenemedi: %s.%s".formatted(typeApiName, spec.apiName()));
        }

        bumpTypeVersion(type.id(), nextVersion);
        writeTypeVersion(type.id(), nextVersion, "Alan eklendi: " + spec.apiName(), ctx);
        log(commitId, "ALTER_TYPE", null, id, null, null,
                Map.of("op", "ADD_PROPERTY", "type", typeApiName, "property", spec.apiName()));
        registry.reload();
        return registry.requireProperty(typeApiName, spec.apiName());
    }

    @Override
    @Transactional
    public LinkTypeDef defineLinkType(NewLinkType spec, CommitContext ctx) {
        UUID commitId = resolveCommit(ctx);
        UUID fromId = registry.requireType(spec.fromTypeApiName()).id();
        UUID toId = registry.requireType(spec.toTypeApiName()).id();

        UUID id;
        try {
            id = jdbc.sql("""
                    INSERT INTO link_type (api_name, display_name, reverse_api_name, reverse_display_name,
                                           from_type_id, to_type_id, cardinality, is_symmetric, property_schema)
                    VALUES (:apiName, :displayName, :reverseApiName, :reverseDisplayName,
                            :fromId, :toId, :cardinality, :isSymmetric, CAST(:schema AS jsonb))
                    RETURNING id
                    """)
                    .param("apiName", spec.apiName())
                    .param("displayName", spec.displayName())
                    .param("reverseApiName", spec.reverseApiName())
                    .param("reverseDisplayName", spec.reverseDisplayName())
                    .param("fromId", fromId)
                    .param("toId", toId)
                    .param("cardinality", spec.cardinality().name())
                    .param("isSymmetric", spec.isSymmetric())
                    .param("schema", codec.writeJson(spec.propertySchema()))
                    .query(UUID.class)
                    .single();
        } catch (DataIntegrityViolationException e) {
            throw translate(e, "İlişki tipi oluşturulamadı: " + spec.apiName());
        }

        log(commitId, "CREATE_TYPE", null, null, id, null,
                Map.of("linkApiName", spec.apiName()));
        registry.reload();
        return registry.requireLinkType(spec.apiName());
    }

    @Override
    @Transactional
    public void deprecateProperty(String typeApiName, String propertyApiName, CommitContext ctx) {
        UUID commitId = resolveCommit(ctx);
        ObjectTypeDef type = registry.requireType(typeApiName);
        PropertyTypeDef property = registry.requireProperty(typeApiName, propertyApiName);
        int nextVersion = type.currentVersion() + 1;

        jdbc.sql("UPDATE property_type SET deprecated_in_version = :v WHERE id = :id")
                .param("v", nextVersion)
                .param("id", property.id())
                .update();

        bumpTypeVersion(type.id(), nextVersion);
        writeTypeVersion(type.id(), nextVersion, "Alan kullanımdan kaldırıldı: " + propertyApiName, ctx);
        log(commitId, "ALTER_TYPE", null, property.id(), null, null,
                Map.of("op", "DEPRECATE_PROPERTY", "type", typeApiName, "property", propertyApiName));
        registry.reload();
    }

    @Override
    public List<ObjectTypeDef> objectTypes() {
        return registry.objectTypes();
    }

    @Override
    public Optional<ObjectTypeDef> objectType(String apiName) {
        return registry.findType(apiName);
    }

    @Override
    public List<LinkTypeDef> linkTypes() {
        return registry.linkTypes();
    }

    @Override
    public void refreshSchema() {
        registry.reload();
    }

    private long objectCount(UUID typeId) {
        return jdbc.sql("SELECT count(*) FROM object_instance WHERE object_type_id = :t AND deleted_at IS NULL")
                .param("t", typeId)
                .query(Long.class)
                .single();
    }

    private void bumpTypeVersion(UUID typeId, int version) {
        jdbc.sql("UPDATE object_type SET current_version = :v WHERE id = :id")
                .param("v", version).param("id", typeId).update();
    }

    private void writeTypeVersion(UUID typeId, int version, String note, CommitContext ctx) {
        jdbc.sql("""
                INSERT INTO object_type_version (object_type_id, version, status, spec, change_note, created_by)
                VALUES (:typeId, :version, 'ACTIVE', CAST(:spec AS jsonb), :note, :createdBy)
                """)
                .param("typeId", typeId)
                .param("version", version)
                .param("spec", codec.writeJson(typeSpecSnapshot(typeId)))
                .param("note", note)
                .param("createdBy", ctx.actorId())
                .update();
    }

    /** Şema anlık görüntüsü — denetim ve replay için sürüm satırına gömülür. */
    private Map<String, Object> typeSpecSnapshot(UUID typeId) {
        List<Map<String, Object>> properties = jdbc.sql("""
                SELECT api_name, data_type, cardinality, is_required, is_title, unit, constraints
                  FROM property_type WHERE object_type_id = :t ORDER BY display_order, api_name
                """)
                .param("t", typeId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("apiName", rs.getString("api_name"));
                    m.put("dataType", rs.getString("data_type"));
                    m.put("cardinality", rs.getString("cardinality"));
                    m.put("isRequired", rs.getBoolean("is_required"));
                    m.put("isTitle", rs.getBoolean("is_title"));
                    m.put("unit", rs.getString("unit"));
                    m.put("constraints", codec.readJsonMap(rs.getString("constraints")));
                    return m;
                })
                .list();
        return Map.of("properties", properties);
    }

    // =====================================================================
    // COMMIT
    // =====================================================================

    @Override
    @Transactional
    public CommitContext openCommit(CommitContext ctx) {
        return ctx.withCommitId(insertCommit(ctx));
    }

    private UUID resolveCommit(CommitContext ctx) {
        return ctx.commitId() != null ? ctx.commitId() : insertCommit(ctx);
    }

    private UUID insertCommit(CommitContext ctx) {
        return jdbc.sql("""
                INSERT INTO ontology_commit (actor_type, actor_id, reason, decision_id, source_id, created_at)
                VALUES (:actorType, :actorId, :reason, :decisionId, :sourceId, :createdAt)
                RETURNING id
                """)
                .param("actorType", ctx.actorType().name())
                .param("actorId", ctx.actorId())
                .param("reason", ctx.reason())
                .param("decisionId", ctx.decisionId())
                .param("sourceId", ctx.dataSourceId())
                .param("createdAt", ts(clock.instant()))
                .query(UUID.class)
                .single();
    }

    private void log(UUID commitId, String op, UUID objectId, UUID propertyTypeId, UUID linkTypeId,
                     Object before, Object after) {
        jdbc.sql("""
                INSERT INTO ontology_change_log (commit_id, op, object_id, property_type_id, link_type_id,
                                                 before, after, occurred_at)
                VALUES (:commitId, :op, :objectId, :ptId, :ltId,
                        CAST(:before AS jsonb), CAST(:after AS jsonb), :at)
                """)
                .param("commitId", commitId)
                .param("op", op)
                .param("objectId", objectId)
                .param("ptId", propertyTypeId)
                .param("ltId", linkTypeId)
                .param("before", before == null ? null : codec.writeJson(before))
                .param("after", after == null ? null : codec.writeJson(after))
                .param("at", ts(clock.instant()))
                .update();
    }

    // =====================================================================
    // NESNE YAZMA
    // =====================================================================

    @Override
    @Transactional
    public ObjectRef createObject(String typeApiName, String externalId, CommitContext ctx) {
        return createObject(typeApiName, externalId, Map.of(), clock.instant(), ctx);
    }

    @Override
    @Transactional
    public ObjectRef createObject(String typeApiName, String externalId,
                                  Map<String, Value> values, Instant validFrom, CommitContext ctx) {
        ObjectTypeDef type = registry.requireType(typeApiName);
        if (type.isAbstract()) {
            throw new OntologyException.SchemaViolation(
                    "'%s' soyut bir tip; doğrudan nesne oluşturulamaz".formatted(typeApiName));
        }
        UUID commitId = resolveCommit(ctx);
        CommitContext bound = ctx.withCommitId(commitId);

        UUID id;
        try {
            id = jdbc.sql("""
                    INSERT INTO object_instance (object_type_id, external_id, created_at, created_commit)
                    VALUES (:typeId, :externalId, :createdAt, :commitId)
                    RETURNING id
                    """)
                    .param("typeId", type.id())
                    .param("externalId", externalId)
                    .param("createdAt", ts(clock.instant()))
                    .param("commitId", commitId)
                    .query(UUID.class)
                    .single();
        } catch (DataIntegrityViolationException e) {
            throw translate(e, "Nesne oluşturulamadı: %s/%s".formatted(typeApiName, externalId));
        }

        ObjectRef ref = new ObjectRef(id, typeApiName, externalId);
        log(commitId, "CREATE_OBJECT", id, null, null, null,
                Map.of("type", typeApiName, "externalId", externalId));

        if (!values.isEmpty()) {
            values.forEach((property, value) ->
                    writeSingle(ref, registry.requireProperty(typeApiName, property), value,
                            validFrom, null, bound));
        }
        requireMandatoryProperties(ref, type);
        refreshProjection(id);
        return ref;
    }

    @Override
    @Transactional
    public ObjectRef findOrCreate(String typeApiName, String externalId, CommitContext ctx) {
        return findByExternalId(typeApiName, externalId)
                .orElseGet(() -> createObject(typeApiName, externalId, ctx));
    }

    private void requireMandatoryProperties(ObjectRef ref, ObjectTypeDef type) {
        List<String> missing = registry.allProperties(type.apiName()).stream()
                .filter(PropertyTypeDef::isRequired)
                .filter(p -> !p.isDeprecated())
                .filter(p -> openValueCount(ref.id(), p.id()) == 0)
                .map(PropertyTypeDef::apiName)
                .toList();
        if (!missing.isEmpty()) {
            throw new OntologyException.SchemaViolation(
                    "Zorunlu alanlar eksik: " + String.join(", ", missing));
        }
    }

    private long openValueCount(UUID objectId, UUID propertyTypeId) {
        return jdbc.sql("""
                SELECT count(*) FROM property_value
                 WHERE object_id = :o AND property_type_id = :p
                   AND valid_to = 'infinity' AND retracted_at IS NULL
                """)
                .param("o", objectId).param("p", propertyTypeId)
                .query(Long.class).single();
    }

    @Override
    @Transactional
    public void deleteObject(ObjectRef obj, CommitContext ctx) {
        UUID commitId = resolveCommit(ctx);
        int updated = jdbc.sql("""
                UPDATE object_instance
                   SET deleted_at = :now, deleted_commit = :commitId
                 WHERE id = :id AND deleted_at IS NULL
                """)
                .param("now", ts(clock.instant()))
                .param("commitId", commitId)
                .param("id", obj.id())
                .update();
        if (updated > 0) {
            log(commitId, "DELETE_OBJECT", obj.id(), null, null, null, Map.of());
            refreshProjection(obj.id());
        }
    }

    // =====================================================================
    // ALAN YAZMA
    // =====================================================================

    @Override
    @Transactional
    public void setProperty(ObjectRef obj, String property, Value value, Instant validFrom, CommitContext ctx) {
        setProperty(obj, property, value, validFrom, null, ctx);
    }

    @Override
    @Transactional
    public void setProperty(ObjectRef obj, String property, Value value,
                            Instant validFrom, Instant validTo, CommitContext ctx) {
        PropertyTypeDef pt = resolveProperty(obj, property);
        if (pt.cardinality() == Cardinality.LIST) {
            throw new OntologyException.SchemaViolation(
                    "'%s' alanı LIST kardinaliteli; setPropertyList kullanın".formatted(property));
        }
        CommitContext bound = ctx.withCommitId(resolveCommit(ctx));
        writeSingle(obj, pt, value, validFrom, validTo, bound);
        refreshProjection(obj.id());
    }

    @Override
    @Transactional
    public void setProperties(ObjectRef obj, Map<String, Value> values, Instant validFrom, CommitContext ctx) {
        CommitContext bound = ctx.withCommitId(resolveCommit(ctx));
        values.forEach((property, value) -> {
            PropertyTypeDef pt = resolveProperty(obj, property);
            if (pt.cardinality() == Cardinality.LIST) {
                throw new OntologyException.SchemaViolation(
                        "'%s' alanı LIST kardinaliteli; setPropertyList kullanın".formatted(property));
            }
            writeSingle(obj, pt, value, validFrom, null, bound);
        });
        refreshProjection(obj.id());
    }

    @Override
    @Transactional
    public void setPropertyList(ObjectRef obj, String property, List<Value> values,
                                Instant validFrom, CommitContext ctx) {
        PropertyTypeDef pt = resolveProperty(obj, property);
        if (pt.cardinality() != Cardinality.LIST) {
            throw new OntologyException.SchemaViolation(
                    "'%s' alanı SINGLE kardinaliteli; setProperty kullanın".formatted(property));
        }
        List<ValueColumns> incoming = values.stream().map(v -> codec.encode(pt, v)).toList();
        List<ValueColumns> existing = openValues(obj.id(), pt.id());

        // Değişmemiş listeyi yeniden yazmak, geçmişe gürültü eklemekten başka bir şey yapmaz.
        if (sameList(existing, incoming, pt.dataType())) {
            return;
        }

        CommitContext bound = ctx.withCommitId(resolveCommit(ctx));
        int existingCount = existing.size();
        for (int ordinal = 0; ordinal < Math.max(existingCount, incoming.size()); ordinal++) {
            closeOrRetractOpen(obj, pt, ordinal, validFrom, bound);
        }
        for (int ordinal = 0; ordinal < incoming.size(); ordinal++) {
            insertValue(obj, pt, ordinal, incoming.get(ordinal), validFrom, null, bound);
        }
        refreshProjection(obj.id());
    }

    @Override
    @Transactional
    public void closeProperty(ObjectRef obj, String property, Instant validTo, CommitContext ctx) {
        PropertyTypeDef pt = resolveProperty(obj, property);
        UUID commitId = resolveCommit(ctx);
        int closed = jdbc.sql("""
                UPDATE property_value
                   SET valid_to = :validTo
                 WHERE object_id = :o AND property_type_id = :p
                   AND retracted_at IS NULL AND valid_to = 'infinity' AND valid_from < :validTo
                """)
                .param("validTo", ts(validTo))
                .param("o", obj.id()).param("p", pt.id())
                .update();
        if (closed > 0) {
            log(commitId, "CLOSE_PROPERTY", obj.id(), pt.id(), null, null,
                    Map.of("property", property, "validTo", validTo.toString()));
            refreshProjection(obj.id());
        }
    }

    @Override
    @Transactional
    public void retractProperty(ObjectRef obj, String property, CommitContext ctx) {
        PropertyTypeDef pt = resolveProperty(obj, property);
        UUID commitId = resolveCommit(ctx);
        int retracted = jdbc.sql("""
                UPDATE property_value
                   SET retracted_at = :now
                 WHERE object_id = :o AND property_type_id = :p
                   AND retracted_at IS NULL AND valid_to = 'infinity'
                """)
                .param("now", ts(clock.instant()))
                .param("o", obj.id()).param("p", pt.id())
                .update();
        if (retracted > 0) {
            log(commitId, "RETRACT_PROPERTY", obj.id(), pt.id(), null, null,
                    Map.of("property", property, "retractedRows", retracted));
            refreshProjection(obj.id());
        }
    }

    // --------------------------------------------------------------- çekirdek

    /**
     * Tek bir alan değerini yazar.
     *
     * <p>Mevcut açık değerle aynıysa hiçbir şey yapmaz — aynı veriyi tekrar tekrar
     * getiren ingest hatları geçmişi gürültüyle doldurmasın diye.
     */
    private void writeSingle(ObjectRef obj, PropertyTypeDef pt, Value value,
                             Instant validFrom, Instant validTo, CommitContext ctx) {
        ValueColumns incoming = codec.encode(pt, value);
        List<ValueColumns> existing = openValues(obj.id(), pt.id());

        if (existing.size() == 1 && sameValue(existing.get(0), incoming, pt.dataType())) {
            return;
        }
        if (validTo == null) {
            closeOrRetractOpen(obj, pt, 0, validFrom, ctx);
        }
        insertValue(obj, pt, 0, incoming, validFrom, validTo, ctx);
    }

    /**
     * {@code validFrom} anında açık olan kaydı kapatır.
     *
     * <ul>
     *   <li>Kayıt daha önce başlamışsa: {@code validFrom}'da kesilir (normal güncelleme).</li>
     *   <li>Kayıt tam aynı anda başlamışsa: geri çekilir — o kayıt baştan yanlıştı.</li>
     *   <li>Kayıt daha sonra başlıyorsa: dokunulmaz; {@code EXCLUDE} kısıtı devreye girer
     *       ve çağıran açıkça {@code retractProperty} çağırmaya zorlanır.</li>
     * </ul>
     */
    private void closeOrRetractOpen(ObjectRef obj, PropertyTypeDef pt, int ordinal,
                                    Instant validFrom, CommitContext ctx) {
        int truncated = jdbc.sql("""
                UPDATE property_value
                   SET valid_to = :validFrom
                 WHERE object_id = :o AND property_type_id = :p AND ordinal = :ord
                   AND retracted_at IS NULL
                   AND valid_from < :validFrom AND valid_to > :validFrom
                """)
                .param("validFrom", ts(validFrom))
                .param("o", obj.id()).param("p", pt.id()).param("ord", ordinal)
                .update();

        int retracted = jdbc.sql("""
                UPDATE property_value
                   SET retracted_at = :now
                 WHERE object_id = :o AND property_type_id = :p AND ordinal = :ord
                   AND retracted_at IS NULL
                   AND valid_from = :validFrom AND valid_to = 'infinity'
                """)
                .param("now", ts(clock.instant()))
                .param("validFrom", ts(validFrom))
                .param("o", obj.id()).param("p", pt.id()).param("ord", ordinal)
                .update();

        if (truncated > 0) {
            log(ctx.commitId(), "CLOSE_PROPERTY", obj.id(), pt.id(), null, null,
                    Map.of("property", pt.apiName(), "ordinal", ordinal, "validTo", validFrom.toString()));
        }
        if (retracted > 0) {
            log(ctx.commitId(), "RETRACT_PROPERTY", obj.id(), pt.id(), null, null,
                    Map.of("property", pt.apiName(), "ordinal", ordinal, "reason", "aynı anda düzeltme"));
        }
    }

    private void insertValue(ObjectRef obj, PropertyTypeDef pt, int ordinal, ValueColumns v,
                             Instant validFrom, Instant validTo, CommitContext ctx) {
        try {
            jdbc.sql("""
                    INSERT INTO property_value (object_id, property_type_id, ordinal,
                                                value_text, value_numeric, value_bool, value_ts,
                                                value_json, value_ref,
                                                valid_from, valid_to, recorded_at,
                                                commit_id, data_source_id, confidence)
                    VALUES (:objectId, :ptId, :ordinal,
                            :vText, :vNumeric, :vBool, :vTs,
                            CAST(:vJson AS jsonb), :vRef,
                            :validFrom, COALESCE(CAST(:validTo AS timestamptz), 'infinity'), :recordedAt,
                            :commitId, :sourceId, :confidence)
                    """)
                    .param("objectId", obj.id())
                    .param("ptId", pt.id())
                    .param("ordinal", ordinal)
                    .param("vText", v.text())
                    .param("vNumeric", v.numeric())
                    .param("vBool", v.bool())
                    .param("vTs", ts(v.timestamp()))
                    .param("vJson", v.json())
                    .param("vRef", v.ref())
                    .param("validFrom", ts(validFrom))
                    .param("validTo", ts(validTo))
                    .param("recordedAt", ts(clock.instant()))
                    .param("commitId", ctx.commitId())
                    .param("sourceId", ctx.dataSourceId())
                    .param("confidence", ctx.confidence())
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw translate(e, "'%s.%s' yazılamadı".formatted(obj.typeApiName(), pt.apiName()));
        }

        log(ctx.commitId(), "SET_PROPERTY", obj.id(), pt.id(), null, null,
                Map.of("property", pt.apiName(), "ordinal", ordinal, "validFrom", validFrom.toString()));
    }

    private List<ValueColumns> openValues(UUID objectId, UUID propertyTypeId) {
        return jdbc.sql("""
                SELECT ordinal, value_text, value_numeric, value_bool, value_ts, value_json, value_ref
                  FROM property_value
                 WHERE object_id = :o AND property_type_id = :p
                   AND valid_to = 'infinity' AND retracted_at IS NULL
                 ORDER BY ordinal
                """)
                .param("o", objectId).param("p", propertyTypeId)
                .query((rs, n) -> new ValueColumns(
                        rs.getString("value_text"),
                        rs.getBigDecimal("value_numeric"),
                        rs.getObject("value_bool") == null ? null : rs.getBoolean("value_bool"),
                        instant(rs, "value_ts"),
                        rs.getString("value_json"),
                        uuid(rs, "value_ref")))
                .list();
    }

    /**
     * İki değerin aynı olup olmadığı.
     *
     * <p>{@link BigDecimal#equals} ölçeğe duyarlıdır ({@code 1.0 != 1.00}), bu yüzden
     * sayısal karşılaştırma {@code compareTo} ile; JSON ise ayrıştırılmış ağaç üzerinden
     * yapılır — boşluk ve anahtar sırası fark yaratmasın.
     */
    private boolean sameValue(ValueColumns a, ValueColumns b, DataType type) {
        return switch (type) {
            case STRING, TEXT, ENUM -> Objects.equals(a.text(), b.text());
            case INTEGER, DECIMAL -> a.numeric() != null && b.numeric() != null
                    && a.numeric().compareTo(b.numeric()) == 0;
            case BOOLEAN -> Objects.equals(a.bool(), b.bool());
            case TIMESTAMP, DATE -> Objects.equals(a.timestamp(), b.timestamp());
            case JSON -> {
                JsonNode left = codec.readJson(a.json());
                JsonNode right = codec.readJson(b.json());
                yield Objects.equals(left, right);
            }
            case REFERENCE -> Objects.equals(a.ref(), b.ref());
        };
    }

    private boolean sameList(List<ValueColumns> a, List<ValueColumns> b, DataType type) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!sameValue(a.get(i), b.get(i), type)) {
                return false;
            }
        }
        return true;
    }

    private PropertyTypeDef resolveProperty(ObjectRef obj, String property) {
        String typeApiName = obj.typeApiName() != null ? obj.typeApiName() : lookupTypeApiName(obj.id());
        return registry.requireProperty(typeApiName, property);
    }

    private String lookupTypeApiName(UUID objectId) {
        return jdbc.sql("""
                SELECT ot.api_name FROM object_instance oi
                  JOIN object_type ot ON ot.id = oi.object_type_id
                 WHERE oi.id = :id
                """)
                .param("id", objectId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new OntologyException.NotFound("Nesne bulunamadı: " + objectId));
    }

    private void refreshProjection(UUID objectId) {
        jdbc.sql("SELECT refresh_object_current(:id)").param("id", objectId).query(Object.class).optional();
    }

    // =====================================================================
    // İLİŞKİLER
    // =====================================================================

    @Override
    @Transactional
    public void link(ObjectRef from, String linkApiName, ObjectRef to,
                     LinkProperties props, Instant validFrom, CommitContext ctx) {
        LinkTypeDef lt = registry.requireLinkType(linkApiName);
        UUID commitId = resolveCommit(ctx);
        LinkProperties p = props == null ? LinkProperties.none() : props;

        if (lt.cardinality().singleTargetPerSource()) {
            // Kaynağın tek hedefi olabilir: mevcut açık bağı kapat — silme.
            jdbc.sql("""
                    UPDATE link_instance SET valid_to = :validFrom
                     WHERE link_type_id = :lt AND from_object_id = :from
                       AND to_object_id <> :to
                       AND retracted_at IS NULL AND valid_to = 'infinity' AND valid_from < :validFrom
                    """)
                    .param("validFrom", ts(validFrom))
                    .param("lt", lt.id()).param("from", from.id()).param("to", to.id())
                    .update();
        }
        if (lt.cardinality().singleSourcePerTarget()) {
            // Hedefin tek kaynağı olabilir: hedefe gelen diğer açık bağları kapat.
            jdbc.sql("""
                    UPDATE link_instance SET valid_to = :validFrom
                     WHERE link_type_id = :lt AND to_object_id = :to
                       AND from_object_id <> :from
                       AND retracted_at IS NULL AND valid_to = 'infinity' AND valid_from < :validFrom
                    """)
                    .param("validFrom", ts(validFrom))
                    .param("lt", lt.id()).param("from", from.id()).param("to", to.id())
                    .update();
        }

        try {
            jdbc.sql("""
                    INSERT INTO link_instance (link_type_id, from_object_id, to_object_id, properties, weight,
                                               valid_from, recorded_at, commit_id, data_source_id, confidence)
                    VALUES (:lt, :from, :to, CAST(:props AS jsonb), :weight,
                            :validFrom, :recordedAt, :commitId, :sourceId, :confidence)
                    """)
                    .param("lt", lt.id())
                    .param("from", from.id())
                    .param("to", to.id())
                    .param("props", codec.writeJson(p.properties()))
                    .param("weight", p.weight())
                    .param("validFrom", ts(validFrom))
                    .param("recordedAt", ts(clock.instant()))
                    .param("commitId", commitId)
                    .param("sourceId", ctx.dataSourceId())
                    .param("confidence", ctx.confidence())
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw translate(e, "İlişki kurulamadı: %s".formatted(linkApiName));
        }

        log(commitId, "ADD_LINK", from.id(), null, lt.id(), null,
                Map.of("link", linkApiName, "to", to.id().toString(), "validFrom", validFrom.toString()));
        refreshProjection(from.id());

        if (lt.isSymmetric()) {
            insertReverseSymmetric(lt, to, from, p, validFrom, commitId, ctx);
        }
    }

    private void insertReverseSymmetric(LinkTypeDef lt, ObjectRef from, ObjectRef to, LinkProperties p,
                                        Instant validFrom, UUID commitId, CommitContext ctx) {
        boolean exists = jdbc.sql("""
                SELECT count(*) FROM link_instance
                 WHERE link_type_id = :lt AND from_object_id = :from AND to_object_id = :to
                   AND retracted_at IS NULL AND valid_to = 'infinity'
                """)
                .param("lt", lt.id()).param("from", from.id()).param("to", to.id())
                .query(Long.class).single() > 0;
        if (exists) {
            return;
        }
        jdbc.sql("""
                INSERT INTO link_instance (link_type_id, from_object_id, to_object_id, properties, weight,
                                           valid_from, recorded_at, commit_id, data_source_id, confidence)
                VALUES (:lt, :from, :to, CAST(:props AS jsonb), :weight,
                        :validFrom, :recordedAt, :commitId, :sourceId, :confidence)
                """)
                .param("lt", lt.id()).param("from", from.id()).param("to", to.id())
                .param("props", codec.writeJson(p.properties()))
                .param("weight", p.weight())
                .param("validFrom", ts(validFrom))
                .param("recordedAt", ts(clock.instant()))
                .param("commitId", commitId)
                .param("sourceId", ctx.dataSourceId())
                .param("confidence", ctx.confidence())
                .update();
        refreshProjection(from.id());
    }

    @Override
    @Transactional
    public void unlink(ObjectRef from, String linkApiName, ObjectRef to, Instant validTo, CommitContext ctx) {
        LinkTypeDef lt = registry.requireLinkType(linkApiName);
        UUID commitId = resolveCommit(ctx);
        int closed = jdbc.sql("""
                UPDATE link_instance SET valid_to = :validTo
                 WHERE link_type_id = :lt AND from_object_id = :from AND to_object_id = :to
                   AND retracted_at IS NULL AND valid_to = 'infinity' AND valid_from < :validTo
                """)
                .param("validTo", ts(validTo))
                .param("lt", lt.id()).param("from", from.id()).param("to", to.id())
                .update();
        if (closed > 0) {
            log(commitId, "CLOSE_LINK", from.id(), null, lt.id(), null,
                    Map.of("link", linkApiName, "to", to.id().toString()));
            refreshProjection(from.id());
        }
        if (lt.isSymmetric()) {
            jdbc.sql("""
                    UPDATE link_instance SET valid_to = :validTo
                     WHERE link_type_id = :lt AND from_object_id = :to AND to_object_id = :from
                       AND retracted_at IS NULL AND valid_to = 'infinity' AND valid_from < :validTo
                    """)
                    .param("validTo", ts(validTo))
                    .param("lt", lt.id()).param("from", from.id()).param("to", to.id())
                    .update();
            refreshProjection(to.id());
        }
    }

    // =====================================================================
    // OKUMA
    // =====================================================================

    @Override
    public Optional<ObjectRef> findByExternalId(String typeApiName, String externalId) {
        ObjectTypeDef type = registry.requireType(typeApiName);
        return jdbc.sql("""
                SELECT id FROM object_instance
                 WHERE object_type_id = :t AND external_id = :e AND deleted_at IS NULL
                """)
                .param("t", type.id()).param("e", externalId)
                .query(UUID.class)
                .optional()
                .map(id -> new ObjectRef(id, typeApiName, externalId));
    }

    @Override
    public Optional<ObjectView> current(ObjectRef obj) {
        return jdbc.sql("""
                SELECT object_id, type_api_name, external_id, title, data, link_summary
                  FROM object_current WHERE object_id = :id
                """)
                .param("id", obj.id())
                .query((rs, n) -> projectionToView(
                        uuid(rs, "object_id"),
                        rs.getString("type_api_name"),
                        rs.getString("external_id"),
                        rs.getString("title"),
                        rs.getString("data"),
                        rs.getString("link_summary")))
                .optional();
    }

    private ObjectView projectionToView(UUID id, String typeApiName, String externalId,
                                        String title, String dataJson, String linksJson) {
        JsonNode data = codec.readJson(dataJson);
        Map<String, Object> decoded = new LinkedHashMap<>();
        for (PropertyTypeDef pt : registry.allProperties(typeApiName)) {
            JsonNode node = data == null ? null : data.get(pt.apiName());
            if (node == null || node.isNull()) {
                continue;
            }
            if (pt.cardinality() == Cardinality.LIST && node.isArray()) {
                List<Object> items = new ArrayList<>();
                node.forEach(child -> items.add(codec.fromJsonNode(pt.dataType(), child)));
                decoded.put(pt.apiName(), List.copyOf(items));
            } else {
                decoded.put(pt.apiName(), codec.fromJsonNode(pt.dataType(), node));
            }
        }
        return new ObjectView(id, typeApiName, externalId, title, null,
                Map.copyOf(decoded), parseLinkSummary(linksJson));
    }

    private Map<String, List<LinkView>> parseLinkSummary(String linksJson) {
        JsonNode links = codec.readJson(linksJson);
        if (links == null || !links.isObject()) {
            return Map.of();
        }
        Map<String, List<LinkView>> result = new LinkedHashMap<>();
        links.properties().forEach(entry -> {
            List<LinkView> views = new ArrayList<>();
            entry.getValue().forEach(node -> views.add(new LinkView(
                    entry.getKey(),
                    UUID.fromString(node.get("objectId").asString()),
                    node.path("typeApiName").asString(null),
                    node.path("externalId").asString(null),
                    null,
                    node.hasNonNull("weight") ? node.get("weight").decimalValue() : null,
                    node.has("properties") ? codec.readJsonMap(node.get("properties").toString()) : Map.of(),
                    null,
                    null)));
            result.put(entry.getKey(), List.copyOf(views));
        });
        return Map.copyOf(result);
    }

    @Override
    public Optional<ObjectView> asOf(ObjectRef obj, Instant knowledgeTime) {
        Optional<ObjectIdentity> identity = identityAsOf(obj.id(), knowledgeTime);
        if (identity.isEmpty()) {
            return Optional.empty();
        }
        ObjectIdentity id = identity.get();
        Map<String, Object> data = propertiesAsOf(obj.id(), id.typeApiName(), knowledgeTime);
        Map<String, List<LinkView>> links = groupLinks(links(obj, knowledgeTime));

        String titleField = registry.allProperties(id.typeApiName()).stream()
                .filter(PropertyTypeDef::isTitle).map(PropertyTypeDef::apiName).findFirst().orElse(null);
        Object title = titleField == null ? null : data.get(titleField);

        return Optional.of(new ObjectView(obj.id(), id.typeApiName(), id.externalId(),
                title == null ? id.externalId() : title.toString(),
                knowledgeTime, data, links));
    }

    private record ObjectIdentity(String typeApiName, String externalId) {
    }

    /**
     * Nesnenin o anda var olup olmadığı da zamansaldır: sonradan yaratılmış bir nesne
     * geçmiş bir sorguda görünmemeli, sonradan silinmiş bir nesne görünmelidir.
     */
    private Optional<ObjectIdentity> identityAsOf(UUID objectId, Instant knowledgeTime) {
        return jdbc.sql("""
                SELECT ot.api_name AS type_api_name, oi.external_id
                  FROM object_instance oi
                  JOIN object_type ot ON ot.id = oi.object_type_id
                 WHERE oi.id = :id
                   AND oi.created_at <= :t
                   AND (oi.deleted_at IS NULL OR oi.deleted_at > :t)
                """)
                .param("id", objectId).param("t", ts(knowledgeTime))
                .query((rs, n) -> new ObjectIdentity(rs.getString("type_api_name"), rs.getString("external_id")))
                .optional();
    }

    private Map<String, Object> propertiesAsOf(UUID objectId, String typeApiName, Instant knowledgeTime) {
        record Row(String apiName, DataType dataType, Cardinality cardinality, int ordinal, Object value) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT pt.api_name, pt.data_type, pt.cardinality, pv.ordinal,
                       pv.value_text, pv.value_numeric, pv.value_bool, pv.value_ts,
                       pv.value_json, pv.value_ref
                  FROM property_value pv
                  JOIN property_type pt ON pt.id = pv.property_type_id
                 WHERE pv.object_id = :id
                   AND pv.valid_from  <= :t AND pv.valid_to > :t
                   AND pv.recorded_at <= :t
                   AND (pv.retracted_at IS NULL OR pv.retracted_at > :t)
                 ORDER BY pt.api_name, pv.ordinal
                """)
                .param("id", objectId).param("t", ts(knowledgeTime))
                .query((rs, n) -> {
                    DataType dt = DataType.valueOf(rs.getString("data_type"));
                    return new Row(rs.getString("api_name"), dt,
                            Cardinality.valueOf(rs.getString("cardinality")),
                            rs.getInt("ordinal"), codec.decode(dt, rs));
                })
                .list();

        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, List<Object>> lists = new LinkedHashMap<>();
        for (Row row : rows) {
            if (row.cardinality() == Cardinality.LIST) {
                lists.computeIfAbsent(row.apiName(), k -> new ArrayList<>()).add(row.value());
            } else {
                data.put(row.apiName(), row.value());
            }
        }
        lists.forEach((k, v) -> data.put(k, List.copyOf(v)));
        return Map.copyOf(data);
    }

    @Override
    public List<PropertyHistoryEntry> history(ObjectRef obj, String property) {
        PropertyTypeDef pt = resolveProperty(obj, property);
        return jdbc.sql("""
                SELECT pv.id, pv.ordinal,
                       pv.value_text, pv.value_numeric, pv.value_bool, pv.value_ts,
                       pv.value_json, pv.value_ref,
                       pv.valid_from,
                       CASE WHEN pv.valid_to = 'infinity' THEN NULL ELSE pv.valid_to END AS valid_to,
                       pv.recorded_at, pv.retracted_at, pv.confidence,
                       c.id AS commit_id, c.actor_type, c.actor_id, c.reason,
                       ds.name AS source_name
                  FROM property_value pv
                  JOIN ontology_commit c ON c.id = pv.commit_id
                  LEFT JOIN data_source ds ON ds.id = pv.data_source_id
                 WHERE pv.object_id = :o AND pv.property_type_id = :p
                 ORDER BY pv.valid_from DESC, pv.recorded_at DESC, pv.ordinal
                """)
                .param("o", obj.id()).param("p", pt.id())
                .query((rs, n) -> new PropertyHistoryEntry(
                        rs.getLong("id"),
                        pt.apiName(),
                        rs.getInt("ordinal"),
                        codec.decode(pt.dataType(), rs),
                        instant(rs, "valid_from"),
                        instant(rs, "valid_to"),
                        instant(rs, "recorded_at"),
                        instant(rs, "retracted_at"),
                        uuid(rs, "commit_id"),
                        ActorType.valueOf(rs.getString("actor_type")),
                        rs.getString("actor_id"),
                        rs.getString("reason"),
                        rs.getString("source_name"),
                        rs.getBigDecimal("confidence")))
                .list();
    }

    @Override
    public List<LinkView> links(ObjectRef obj, Instant knowledgeTime) {
        String temporal = knowledgeTime == null
                ? "li.valid_to = 'infinity' AND li.retracted_at IS NULL"
                : """
                  li.valid_from <= :t AND li.valid_to > :t
                  AND li.recorded_at <= :t
                  AND (li.retracted_at IS NULL OR li.retracted_at > :t)
                  """;

        JdbcClient.StatementSpec spec = jdbc.sql("""
                SELECT lt.api_name AS link_api_name,
                       li.to_object_id, tot.api_name AS target_type, tgt.external_id AS target_external_id,
                       oc.title AS target_title, li.weight, li.properties,
                       li.valid_from,
                       CASE WHEN li.valid_to = 'infinity' THEN NULL ELSE li.valid_to END AS valid_to
                  FROM link_instance li
                  JOIN link_type       lt  ON lt.id  = li.link_type_id
                  JOIN object_instance tgt ON tgt.id = li.to_object_id
                  JOIN object_type     tot ON tot.id = tgt.object_type_id
                  LEFT JOIN object_current oc ON oc.object_id = tgt.id
                 WHERE li.from_object_id = :from AND %s
                 ORDER BY lt.api_name, li.valid_from DESC
                """.formatted(temporal))
                .param("from", obj.id());

        if (knowledgeTime != null) {
            spec = spec.param("t", ts(knowledgeTime));
        }

        return spec.query((rs, n) -> new LinkView(
                        rs.getString("link_api_name"),
                        uuid(rs, "to_object_id"),
                        rs.getString("target_type"),
                        rs.getString("target_external_id"),
                        rs.getString("target_title"),
                        rs.getBigDecimal("weight"),
                        codec.readJsonMap(rs.getString("properties")),
                        instant(rs, "valid_from"),
                        instant(rs, "valid_to")))
                .list();
    }

    private static Map<String, List<LinkView>> groupLinks(List<LinkView> links) {
        Map<String, List<LinkView>> grouped = new LinkedHashMap<>();
        links.forEach(l -> grouped.computeIfAbsent(l.linkApiName(), k -> new ArrayList<>()).add(l));
        Map<String, List<LinkView>> immutable = new LinkedHashMap<>();
        grouped.forEach((k, v) -> immutable.put(k, List.copyOf(v)));
        return Map.copyOf(immutable);
    }

    @Override
    public QueryResult query(OntologyQuery query) {
        registry.requireType(query.type());
        return query.asOf() == null ? queryCurrent(query) : queryAsOf(query);
    }

    private QueryResult queryCurrent(OntologyQuery query) {
        CompiledQuery compiled = queryCompiler.compileCurrent(query);
        List<ObjectView> objects = bind(compiled.sql(), compiled.params())
                .query((rs, n) -> projectionToView(
                        uuid(rs, "object_id"),
                        rs.getString("type_api_name"),
                        rs.getString("external_id"),
                        rs.getString("title"),
                        rs.getString("data"),
                        rs.getString("link_summary")))
                .list();
        long total = bind(compiled.countSql(), compiled.params()).query(Long.class).single();
        return new QueryResult(applyTraversals(objects, query), total,
                query.offset() + objects.size() < total);
    }

    /**
     * As-of yolu önce eşleşen kimlikleri bulur, sonra her nesnenin görünümünü tek tek kurar.
     *
     * <p>Sonuç sayısı {@code limit} ile sınırlı (en fazla 500) ve bu yol denetim/backtest
     * yolu — sıcak yol değil. Tek sorguda birleştirmenin karmaşıklığı, kazanılan
     * gecikmeye değmiyor.
     */
    private QueryResult queryAsOf(OntologyQuery query) {
        CompiledQuery compiled = queryCompiler.compileAsOf(query);
        List<UUID> ids = bind(compiled.sql(), compiled.params()).query(UUID.class).list();
        long total = bind(compiled.countSql(), compiled.params()).query(Long.class).single();

        List<ObjectView> objects = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            asOf(ObjectRef.of(id), query.asOf()).ifPresent(objects::add);
        }
        return new QueryResult(applyTraversals(objects, query), total,
                query.offset() + ids.size() < total);
    }

    /** {@code traverse} verilmişse yalnızca istenen ilişkiler görünümde bırakılır. */
    private List<ObjectView> applyTraversals(List<ObjectView> objects, OntologyQuery query) {
        if (query.traverse().isEmpty()) {
            return objects;
        }
        List<String> wanted = query.traverse().stream()
                .map(t -> registry.requireLinkType(t.linkApiName()).apiName())
                .toList();
        return objects.stream()
                .map(view -> {
                    Map<String, List<LinkView>> filtered = new LinkedHashMap<>();
                    wanted.forEach(name -> {
                        List<LinkView> links = view.links().get(name);
                        if (links != null) {
                            filtered.put(name, links);
                        }
                    });
                    return new ObjectView(view.objectId(), view.typeApiName(), view.externalId(),
                            view.title(), view.knowledgeTime(), view.data(), Map.copyOf(filtered));
                })
                .toList();
    }

    private JdbcClient.StatementSpec bind(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        return spec;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public OntologySnapshot snapshot(Instant knowledgeTime) {
        return new DefaultOntologySnapshot(this, knowledgeTime);
    }
}
