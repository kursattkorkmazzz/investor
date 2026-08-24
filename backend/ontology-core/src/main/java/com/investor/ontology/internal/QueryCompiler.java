package com.investor.ontology.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.investor.ontology.OntologyException;
import com.investor.ontology.model.Cardinality;
import com.investor.ontology.model.DataType;
import com.investor.ontology.model.ObjectTypeDef;
import com.investor.ontology.model.PropertyTypeDef;
import com.investor.ontology.query.Filter;
import com.investor.ontology.query.OntologyQuery;
import com.investor.ontology.query.Operator;
import com.investor.ontology.query.SortSpec;

import static com.investor.ontology.internal.SqlSupport.ts;

/**
 * Dinamik sorgu DSL'ini SQL'e çevirir.
 *
 * <h2>Güvenlik</h2>
 * SQL metnine giren her tanımlayıcı önce {@code property_type} kayıtlarına karşı çözülür;
 * çözülemeyen bir alan adı hata verir, metne asla geçmez. Operatörler kapalı bir enum'dan
 * gelir. Tüm değerler adlandırılmış parametre olarak bağlanır. Yani kullanıcı girdisinden
 * türeyen hiçbir metin SQL'e birleştirilmez.
 *
 * <h2>İki yol</h2>
 * <ul>
 *   <li><b>Güncel:</b> {@code object_current} projeksiyonu üzerinden — hızlı yol.</li>
 *   <li><b>As-of:</b> {@code property_value} üzerinden, "o anda bildiğimiz" filtresiyle —
 *       denetim ve backtest yolu. Nesne kimliklerini döner; görünümler tek tek kurulur.</li>
 * </ul>
 */
final class QueryCompiler {

    private final SchemaRegistry registry;

    QueryCompiler(SchemaRegistry registry) {
        this.registry = registry;
    }

    // =====================================================================
    // GÜNCEL YOL — object_current
    // =====================================================================

    CompiledQuery compileCurrent(OntologyQuery query) {
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> predicates = new ArrayList<>();

        params.put("types", typeAndDescendants(query.type()));
        predicates.add("oc.type_api_name IN (:types)");

        if (query.search() != null && !query.search().isBlank()) {
            params.put("search", "%" + query.search().trim() + "%");
            predicates.add("oc.title ILIKE :search");
        }

        int i = 0;
        for (Filter filter : query.where()) {
            PropertyTypeDef property = registry.requireProperty(query.type(), filter.field());
            predicates.add(currentPredicate(property, filter, "f" + i, params));
            i++;
        }

        String where = String.join("\n   AND ", predicates);
        StringBuilder order = new StringBuilder();
        if (query.orderBy().isEmpty()) {
            order.append("oc.title");
        } else {
            List<String> parts = new ArrayList<>();
            for (SortSpec sort : query.orderBy()) {
                parts.add(currentSortExpression(query.type(), sort));
            }
            parts.add("oc.object_id");
            order.append(String.join(", ", parts));
        }

        params.put("limit", query.limit());
        params.put("offset", query.offset());

        String sql = """
                SELECT oc.object_id, oc.type_api_name, oc.external_id, oc.title, oc.data, oc.link_summary
                  FROM object_current oc
                 WHERE %s
                 ORDER BY %s
                 LIMIT :limit OFFSET :offset
                """.formatted(where, order);

        String countSql = "SELECT count(*) FROM object_current oc WHERE " + where;
        return new CompiledQuery(sql, countSql, params);
    }

    private String currentPredicate(PropertyTypeDef property, Filter filter, String key,
                                    Map<String, Object> params) {
        String field = property.apiName();

        if (property.cardinality() == Cardinality.LIST) {
            return listPredicate(property, filter, key, params, field);
        }

        String accessor = switch (property.dataType()) {
            case INTEGER, DECIMAL -> "(oc.data ->> '%s')::numeric".formatted(field);
            case BOOLEAN -> "(oc.data ->> '%s')::boolean".formatted(field);
            case TIMESTAMP, DATE -> "(oc.data ->> '%s')::timestamptz".formatted(field);
            default -> "(oc.data ->> '%s')".formatted(field);
        };

        return switch (filter.op()) {
            case IS_NULL -> "(oc.data -> '%s') IS NULL".formatted(field);
            case IS_NOT_NULL -> "(oc.data -> '%s') IS NOT NULL".formatted(field);
            case CONTAINS -> {
                requireTextual(property, filter.op());
                params.put(key, "%" + filter.value() + "%");
                yield accessor + " ILIKE :" + key;
            }
            case STARTS_WITH -> {
                requireTextual(property, filter.op());
                params.put(key, filter.value() + "%");
                yield accessor + " ILIKE :" + key;
            }
            case IN -> {
                params.put(key, coerceList(property, (List<?>) filter.value()));
                yield accessor + " IN (:" + key + ")";
            }
            case EQ -> {
                params.put(key, coerce(property, filter.value()));
                yield accessor + " = :" + key;
            }
            case NEQ -> {
                params.put(key, coerce(property, filter.value()));
                yield accessor + " IS DISTINCT FROM :" + key;
            }
            case GT -> comparison(property, filter, key, params, accessor, ">");
            case GTE -> comparison(property, filter, key, params, accessor, ">=");
            case LT -> comparison(property, filter, key, params, accessor, "<");
            case LTE -> comparison(property, filter, key, params, accessor, "<=");
        };
    }

    private String listPredicate(PropertyTypeDef property, Filter filter, String key,
                                 Map<String, Object> params, String field) {
        return switch (filter.op()) {
            case EQ, CONTAINS -> {
                params.put(key, jsonScalar(property, filter.value()));
                yield "(oc.data -> '%s') @> CAST(:%s AS jsonb)".formatted(field, key);
            }
            case IS_NULL -> "(oc.data -> '%s') IS NULL".formatted(field);
            case IS_NOT_NULL -> "(oc.data -> '%s') IS NOT NULL".formatted(field);
            default -> throw new OntologyException.SchemaViolation(
                    "'%s' LIST kardinaliteli bir alan; %s operatörü desteklenmiyor (EQ/CONTAINS kullanın)"
                            .formatted(field, filter.op()));
        };
    }

    private String comparison(PropertyTypeDef property, Filter filter, String key,
                              Map<String, Object> params, String accessor, String op) {
        if (property.dataType().isTextual() || property.dataType() == DataType.JSON
                || property.dataType() == DataType.REFERENCE) {
            throw new OntologyException.SchemaViolation(
                    "'%s' alanı %s tipinde; %s karşılaştırması desteklenmiyor"
                            .formatted(property.apiName(), property.dataType(), op));
        }
        params.put(key, coerce(property, filter.value()));
        return accessor + " " + op + " :" + key;
    }

    private String currentSortExpression(String typeApiName, SortSpec sort) {
        String direction = sort.direction() == SortSpec.Direction.DESC ? "DESC NULLS LAST" : "ASC NULLS LAST";
        String builtIn = switch (sort.field()) {
            case "title" -> "oc.title";
            case "externalId" -> "oc.external_id";
            case "updatedAt" -> "oc.updated_at";
            default -> null;
        };
        if (builtIn != null) {
            return builtIn + " " + direction;
        }
        PropertyTypeDef property = registry.requireProperty(typeApiName, sort.field());
        String accessor = switch (property.dataType()) {
            case INTEGER, DECIMAL -> "(oc.data ->> '%s')::numeric".formatted(property.apiName());
            case TIMESTAMP, DATE -> "(oc.data ->> '%s')::timestamptz".formatted(property.apiName());
            case BOOLEAN -> "(oc.data ->> '%s')::boolean".formatted(property.apiName());
            default -> "(oc.data ->> '%s')".formatted(property.apiName());
        };
        return accessor + " " + direction;
    }

    // =====================================================================
    // AS-OF YOL — property_value
    // =====================================================================

    /** "O anda bildiğimiz" filtresi. Hem geçerlilik hem kayıt zamanını dikkate alır. */
    private static final String TEMPORAL_PREDICATE = """
            pv.valid_from  <= :asOf AND pv.valid_to > :asOf
              AND pv.recorded_at <= :asOf
              AND (pv.retracted_at IS NULL OR pv.retracted_at > :asOf)
            """;

    CompiledQuery compileAsOf(OntologyQuery query) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("asOf", ts(query.asOf()));
        params.put("types", typeAndDescendants(query.type()));

        List<String> predicates = new ArrayList<>();
        predicates.add("ot.api_name IN (:types)");
        predicates.add("oi.created_at <= :asOf");
        predicates.add("(oi.deleted_at IS NULL OR oi.deleted_at > :asOf)");

        int i = 0;
        for (Filter filter : query.where()) {
            PropertyTypeDef property = registry.requireProperty(query.type(), filter.field());
            params.put("pt" + i, property.id());
            predicates.add(asOfPredicate(property, filter, i, params));
            i++;
        }

        StringBuilder joins = new StringBuilder();
        List<String> orderParts = new ArrayList<>();
        int s = 0;
        for (SortSpec sort : query.orderBy()) {
            String direction = sort.direction() == SortSpec.Direction.DESC
                    ? "DESC NULLS LAST" : "ASC NULLS LAST";
            if ("externalId".equals(sort.field())) {
                orderParts.add("oi.external_id " + direction);
                continue;
            }
            if ("createdAt".equals(sort.field())) {
                orderParts.add("oi.created_at " + direction);
                continue;
            }
            PropertyTypeDef property = registry.requireProperty(query.type(), sort.field());
            String alias = "srt" + s;
            params.put("sortPt" + s, property.id());
            joins.append("""
                      LEFT JOIN LATERAL (
                          SELECT pv.value_text, pv.value_numeric, pv.value_ts
                            FROM property_value pv
                           WHERE pv.object_id = oi.id AND pv.property_type_id = :sortPt%d
                             AND %s
                           ORDER BY pv.ordinal
                           LIMIT 1
                      ) %s ON true
                    """.formatted(s, TEMPORAL_PREDICATE, alias));
            orderParts.add(switch (property.dataType()) {
                case INTEGER, DECIMAL -> alias + ".value_numeric " + direction;
                case TIMESTAMP, DATE -> alias + ".value_ts " + direction;
                default -> alias + ".value_text " + direction;
            });
            s++;
        }
        orderParts.add("oi.id");

        params.put("limit", query.limit());
        params.put("offset", query.offset());

        String where = String.join("\n   AND ", predicates);
        String sql = """
                SELECT oi.id
                  FROM object_instance oi
                  JOIN object_type ot ON ot.id = oi.object_type_id
                %s WHERE %s
                 ORDER BY %s
                 LIMIT :limit OFFSET :offset
                """.formatted(joins, where, String.join(", ", orderParts));

        String countSql = """
                SELECT count(*)
                  FROM object_instance oi
                  JOIN object_type ot ON ot.id = oi.object_type_id
                 WHERE %s
                """.formatted(where);

        return new CompiledQuery(sql, countSql, params);
    }

    private String asOfPredicate(PropertyTypeDef property, Filter filter, int index,
                                 Map<String, Object> params) {
        String key = "v" + index;
        String column = property.dataType().column();

        String valueCondition = switch (filter.op()) {
            case IS_NULL, IS_NOT_NULL -> null;
            case CONTAINS -> {
                requireTextual(property, filter.op());
                params.put(key, "%" + filter.value() + "%");
                yield "pv." + column + " ILIKE :" + key;
            }
            case STARTS_WITH -> {
                requireTextual(property, filter.op());
                params.put(key, filter.value() + "%");
                yield "pv." + column + " ILIKE :" + key;
            }
            case IN -> {
                params.put(key, coerceList(property, (List<?>) filter.value()));
                yield "pv." + column + " IN (:" + key + ")";
            }
            case EQ -> {
                params.put(key, coerce(property, filter.value()));
                yield "pv." + column + " = :" + key;
            }
            case NEQ -> {
                params.put(key, coerce(property, filter.value()));
                yield "pv." + column + " IS DISTINCT FROM :" + key;
            }
            case GT, GTE, LT, LTE -> {
                if (property.dataType().isTextual() || property.dataType() == DataType.JSON
                        || property.dataType() == DataType.REFERENCE) {
                    throw new OntologyException.SchemaViolation(
                            "'%s' alanı %s tipinde; sıralama karşılaştırması desteklenmiyor"
                                    .formatted(property.apiName(), property.dataType()));
                }
                params.put(key, coerce(property, filter.value()));
                String op = switch (filter.op()) {
                    case GT -> ">";
                    case GTE -> ">=";
                    case LT -> "<";
                    default -> "<=";
                };
                yield "pv." + column + " " + op + " :" + key;
            }
        };

        String exists = """
                EXISTS (SELECT 1 FROM property_value pv
                         WHERE pv.object_id = oi.id AND pv.property_type_id = :pt%d
                           AND %s%s)
                """.formatted(index, TEMPORAL_PREDICATE,
                valueCondition == null ? "" : "\n   AND " + valueCondition);

        return filter.op() == Operator.IS_NULL ? "NOT " + exists : exists;
    }

    // =====================================================================
    // Yardımcılar
    // =====================================================================

    /** Bir tip sorgusu, alt tiplerini de kapsar — kalıtım okuma tarafında da geçerlidir. */
    private List<String> typeAndDescendants(String typeApiName) {
        registry.requireType(typeApiName);
        Set<String> result = new HashSet<>();
        result.add(typeApiName);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (ObjectTypeDef candidate : registry.objectTypes()) {
                if (candidate.parentTypeApiName() != null
                        && result.contains(candidate.parentTypeApiName())
                        && result.add(candidate.apiName())) {
                    grew = true;
                }
            }
        }
        return List.copyOf(result);
    }

    private static void requireTextual(PropertyTypeDef property, Operator op) {
        if (!property.dataType().isTextual()) {
            throw new OntologyException.SchemaViolation(
                    "%s operatörü yalnızca metin alanlarında kullanılabilir; '%s' %s tipinde"
                            .formatted(op, property.apiName(), property.dataType()));
        }
    }

    /** DSL'den gelen ham JSON değerini alanın tipine çevirir. */
    private static Object coerce(PropertyTypeDef property, Object raw) {
        if (raw == null) {
            return null;
        }
        return switch (property.dataType()) {
            case INTEGER, DECIMAL -> raw instanceof BigDecimal d ? d : new BigDecimal(raw.toString());
            case BOOLEAN -> raw instanceof Boolean b ? b : Boolean.parseBoolean(raw.toString());
            case TIMESTAMP, DATE -> ts(raw instanceof Instant i ? i : Instant.parse(raw.toString()));
            case REFERENCE -> raw instanceof UUID u ? u : UUID.fromString(raw.toString());
            default -> raw.toString();
        };
    }

    private static List<Object> coerceList(PropertyTypeDef property, List<?> raw) {
        List<Object> values = new ArrayList<>(raw.size());
        raw.forEach(item -> values.add(coerce(property, item)));
        return values;
    }

    /** LIST alanlarında containment sorgusu için tek elemanlı JSON dizisi üretir. */
    private static String jsonScalar(PropertyTypeDef property, Object raw) {
        return switch (property.dataType()) {
            case INTEGER, DECIMAL, BOOLEAN -> "[" + raw + "]";
            default -> "[\"" + raw.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
        };
    }
}
