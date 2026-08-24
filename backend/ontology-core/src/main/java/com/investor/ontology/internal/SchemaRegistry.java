package com.investor.ontology.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import com.investor.ontology.OntologyException;
import com.investor.ontology.model.Cardinality;
import com.investor.ontology.model.DataType;
import com.investor.ontology.model.LinkCardinality;
import com.investor.ontology.model.LinkTypeDef;
import com.investor.ontology.model.ObjectTypeDef;
import com.investor.ontology.model.PropertyTypeDef;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Meta katmanın bellek içi görünümü.
 *
 * <p>Şema her okuma sorgusunda gerekiyor (alan adı doğrulama, tip çözümleme) ama nadiren
 * değişiyor. Tamamı bellekte tutulur ve her şema yazımından sonra yeniden yüklenir.
 *
 * <p>Görünüm değişmez bir anlık görüntü olarak {@code volatile} bir alanda tutulur:
 * okuyucular kilit almaz, yazıcılar tam görünümü atomik olarak değiştirir.
 */
final class SchemaRegistry {

    private final JdbcClient jdbc;
    private final ValueCodec codec;
    private final ReentrantLock reloadLock = new ReentrantLock();

    /** {@code null} = henüz yüklenmedi. Tembel yükleme, şema kaydını migration sırasından bağımsız kılar. */
    private volatile Snapshot snapshot;

    SchemaRegistry(JdbcClient jdbc, ValueCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    record Snapshot(
            Map<String, ObjectTypeDef> typesByApiName,
            Map<UUID, ObjectTypeDef> typesById,
            Map<String, LinkTypeDef> linkTypesByApiName,
            Map<UUID, String> propertyApiNameById) {

        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    /**
     * Şemayı ilk erişimde yükler.
     *
     * <p>Bean oluşturma sırasında yüklemiyoruz: {@code JdbcClient} bean'i Flyway
     * migration'ından önce hazır olabiliyor ve tablolar henüz yokken sorgu patlıyor.
     * İlk gerçek erişim, migration'ın çoktan bittiği bir andır.
     */
    private Snapshot snapshot() {
        Snapshot local = snapshot;
        if (local == null) {
            reloadLock.lock();
            try {
                if (snapshot == null) {
                    reload();
                }
                local = snapshot;
            } finally {
                reloadLock.unlock();
            }
        }
        return local;
    }

    void reload() {
        reloadLock.lock();
        try {
            Map<UUID, List<PropertyTypeDef>> propsByType = loadProperties();

            Map<String, ObjectTypeDef> byApiName = new LinkedHashMap<>();
            Map<UUID, ObjectTypeDef> byId = new HashMap<>();
            Map<UUID, String> propertyNames = new HashMap<>();

            jdbc.sql("""
                    SELECT ot.id, ot.api_name, ot.display_name, ot.description, ot.icon,
                           ot.is_abstract, ot.current_version, parent.api_name AS parent_api_name
                      FROM object_type ot
                      LEFT JOIN object_type parent ON parent.id = ot.parent_type_id
                     ORDER BY ot.api_name
                    """)
                    .query((rs, rowNum) -> {
                        UUID id = UUID.fromString(rs.getString("id"));
                        return new ObjectTypeDef(
                                id,
                                rs.getString("api_name"),
                                rs.getString("display_name"),
                                rs.getString("description"),
                                rs.getString("icon"),
                                rs.getBoolean("is_abstract"),
                                rs.getString("parent_api_name"),
                                rs.getInt("current_version"),
                                propsByType.getOrDefault(id, List.of()));
                    })
                    .list()
                    .forEach(type -> {
                        byApiName.put(type.apiName(), type);
                        byId.put(type.id(), type);
                        type.properties().forEach(p -> propertyNames.put(p.id(), p.apiName()));
                    });

            Map<String, LinkTypeDef> links = new LinkedHashMap<>();
            jdbc.sql("""
                    SELECT lt.id, lt.api_name, lt.display_name, lt.reverse_api_name,
                           lt.reverse_display_name, lt.cardinality, lt.is_symmetric,
                           lt.property_schema,
                           src.api_name AS from_api_name, tgt.api_name AS to_api_name
                      FROM link_type lt
                      JOIN object_type src ON src.id = lt.from_type_id
                      JOIN object_type tgt ON tgt.id = lt.to_type_id
                     ORDER BY lt.api_name
                    """)
                    .query((rs, rowNum) -> new LinkTypeDef(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("api_name"),
                            rs.getString("display_name"),
                            rs.getString("reverse_api_name"),
                            rs.getString("reverse_display_name"),
                            rs.getString("from_api_name"),
                            rs.getString("to_api_name"),
                            LinkCardinality.valueOf(rs.getString("cardinality")),
                            rs.getBoolean("is_symmetric"),
                            codec.readJsonMap(rs.getString("property_schema"))))
                    .list()
                    .forEach(lt -> links.put(lt.apiName(), lt));

            this.snapshot = new Snapshot(
                    Map.copyOf(byApiName), Map.copyOf(byId), Map.copyOf(links), Map.copyOf(propertyNames));
        } finally {
            reloadLock.unlock();
        }
    }

    private Map<UUID, List<PropertyTypeDef>> loadProperties() {
        Map<UUID, List<PropertyTypeDef>> result = new HashMap<>();
        jdbc.sql("""
                SELECT id, object_type_id, api_name, display_name, description, data_type,
                       cardinality, is_required, is_title, unit, constraints,
                       introduced_in_version, deprecated_in_version, display_order
                  FROM property_type
                 ORDER BY object_type_id, display_order, api_name
                """)
                .query((rs, rowNum) -> new PropertyTypeDef(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("object_type_id")),
                        rs.getString("api_name"),
                        rs.getString("display_name"),
                        rs.getString("description"),
                        DataType.valueOf(rs.getString("data_type")),
                        Cardinality.valueOf(rs.getString("cardinality")),
                        rs.getBoolean("is_required"),
                        rs.getBoolean("is_title"),
                        rs.getString("unit"),
                        codec.readJsonMap(rs.getString("constraints")),
                        (Integer) rs.getObject("introduced_in_version"),
                        (Integer) rs.getObject("deprecated_in_version"),
                        rs.getInt("display_order")))
                .list()
                .forEach(p -> result.computeIfAbsent(p.objectTypeId(), k -> new ArrayList<>()).add(p));
        return result;
    }

    // ------------------------------------------------------------------ okuma

    List<ObjectTypeDef> objectTypes() {
        return List.copyOf(snapshot().typesByApiName().values());
    }

    Optional<ObjectTypeDef> findType(String apiName) {
        return Optional.ofNullable(snapshot().typesByApiName().get(apiName));
    }

    ObjectTypeDef requireType(String apiName) {
        ObjectTypeDef type = snapshot().typesByApiName().get(apiName);
        if (type == null) {
            throw new OntologyException.NotFound("Tip bulunamadı: " + apiName);
        }
        return type;
    }

    ObjectTypeDef requireType(UUID id) {
        ObjectTypeDef type = snapshot().typesById().get(id);
        if (type == null) {
            throw new OntologyException.NotFound("Tip bulunamadı: " + id);
        }
        return type;
    }

    /**
     * Alanı, tip hiyerarşisini yukarı doğru tarayarak çözer — kalıtılan alanlar da bulunur.
     */
    PropertyTypeDef requireProperty(String typeApiName, String propertyApiName) {
        ObjectTypeDef type = requireType(typeApiName);
        ObjectTypeDef cursor = type;
        while (cursor != null) {
            Optional<PropertyTypeDef> found = cursor.property(propertyApiName);
            if (found.isPresent()) {
                return found.get();
            }
            cursor = cursor.parentTypeApiName() == null
                    ? null
                    : snapshot().typesByApiName().get(cursor.parentTypeApiName());
        }
        throw new OntologyException.NotFound(
                "'%s' tipinde '%s' alanı yok".formatted(typeApiName, propertyApiName));
    }

    Optional<PropertyTypeDef> findProperty(String typeApiName, String propertyApiName) {
        try {
            return Optional.of(requireProperty(typeApiName, propertyApiName));
        } catch (OntologyException.NotFound e) {
            return Optional.empty();
        }
    }

    /** Tipin kendi ve kalıtılan tüm alanları. */
    List<PropertyTypeDef> allProperties(String typeApiName) {
        List<PropertyTypeDef> all = new ArrayList<>();
        ObjectTypeDef cursor = requireType(typeApiName);
        while (cursor != null) {
            for (PropertyTypeDef p : cursor.properties()) {
                if (all.stream().noneMatch(e -> e.apiName().equals(p.apiName()))) {
                    all.add(p);
                }
            }
            cursor = cursor.parentTypeApiName() == null
                    ? null
                    : snapshot().typesByApiName().get(cursor.parentTypeApiName());
        }
        return all;
    }

    List<LinkTypeDef> linkTypes() {
        return List.copyOf(snapshot().linkTypesByApiName().values());
    }

    LinkTypeDef requireLinkType(String apiName) {
        LinkTypeDef lt = snapshot().linkTypesByApiName().get(apiName);
        if (lt == null) {
            throw new OntologyException.NotFound("İlişki tipi bulunamadı: " + apiName);
        }
        return lt;
    }

    String propertyApiName(UUID propertyTypeId) {
        return snapshot().propertyApiNameById().get(propertyTypeId);
    }
}
