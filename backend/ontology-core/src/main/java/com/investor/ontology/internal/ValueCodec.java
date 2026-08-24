package com.investor.ontology.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.investor.ontology.OntologyException;
import com.investor.ontology.model.DataType;
import com.investor.ontology.model.PropertyTypeDef;
import com.investor.ontology.model.Value;
import com.investor.ontology.model.Value.BooleanValue;
import com.investor.ontology.model.Value.JsonValue;
import com.investor.ontology.model.Value.NumericValue;
import com.investor.ontology.model.Value.ReferenceValue;
import com.investor.ontology.model.Value.TextValue;
import com.investor.ontology.model.Value.TimestampValue;

/** {@link Value} ile veritabanı kolonları arasındaki çeviri ve şema doğrulaması. */
final class ValueCodec {

    private final ObjectMapper objectMapper;

    ValueCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ yazma

    /** Değeri kolonlara çevirir ve alan tanımına uygunluğunu doğrular. */
    ValueColumns encode(PropertyTypeDef property, Value value) {
        DataType type = property.dataType();
        ValueColumns columns = switch (value) {
            case TextValue v -> {
                requireType(property, type.isTextual(), "metin");
                validateText(property, v.value());
                yield ValueColumns.ofText(v.value());
            }
            case NumericValue v -> {
                requireType(property, type.isNumeric(), "sayı");
                validateNumeric(property, v.value());
                yield ValueColumns.ofNumeric(normalizeInteger(property, v.value()));
            }
            case BooleanValue v -> {
                requireType(property, type == DataType.BOOLEAN, "mantıksal");
                yield ValueColumns.ofBool(v.value());
            }
            case TimestampValue v -> {
                requireType(property, type == DataType.TIMESTAMP || type == DataType.DATE, "zaman");
                yield ValueColumns.ofTimestamp(v.value());
            }
            case JsonValue v -> {
                requireType(property, type == DataType.JSON, "json");
                validateJson(property, v.json());
                yield ValueColumns.ofJson(v.json());
            }
            case ReferenceValue v -> {
                requireType(property, type == DataType.REFERENCE, "referans");
                yield ValueColumns.ofRef(v.objectId());
            }
        };
        return columns;
    }

    // ------------------------------------------------------------------ okuma

    /** {@code property_value} satırından Java değerini çıkarır. */
    Object decode(DataType type, ResultSet rs) throws SQLException {
        return switch (type) {
            case STRING, TEXT, ENUM -> rs.getString("value_text");
            case INTEGER, DECIMAL -> rs.getBigDecimal("value_numeric");
            case BOOLEAN -> {
                boolean b = rs.getBoolean("value_bool");
                yield rs.wasNull() ? null : b;
            }
            case TIMESTAMP, DATE -> {
                Timestamp ts = rs.getTimestamp("value_ts");
                yield ts == null ? null : ts.toInstant();
            }
            case JSON -> readJson(rs.getString("value_json"));
            case REFERENCE -> {
                String raw = rs.getString("value_ref");
                yield raw == null ? null : UUID.fromString(raw);
            }
        };
    }

    /** {@code object_current.data} içindeki JSON değerini Java tipine çevirir. */
    Object fromJsonNode(DataType type, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return switch (type) {
            case STRING, TEXT, ENUM -> node.asString();
            case INTEGER, DECIMAL -> node.decimalValue();
            case BOOLEAN -> node.asBoolean();
            case TIMESTAMP, DATE -> Instant.parse(node.asString());
            case JSON -> node;
            case REFERENCE -> UUID.fromString(node.asString());
        };
    }

    JsonNode readJson(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new OntologyException("JSON okunamadı: " + raw, e);
        }
    }

    String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new OntologyException("JSON yazılamadı", e);
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> readJsonMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, Map.class);
        } catch (Exception e) {
            throw new OntologyException("JSON nesnesi okunamadı: " + raw, e);
        }
    }

    // ------------------------------------------------------------- doğrulama

    private static void requireType(PropertyTypeDef property, boolean ok, String given) {
        if (!ok) {
            throw new OntologyException.SchemaViolation(
                    "'%s' alanı %s tipinde; %s değeri kabul edilmiyor"
                            .formatted(property.apiName(), property.dataType(), given));
        }
    }

    private void validateText(PropertyTypeDef property, String text) {
        Map<String, Object> c = property.constraints();

        if (property.dataType() == DataType.ENUM) {
            Object allowed = c.get("enumValues");
            if (allowed instanceof Collection<?> values && !values.contains(text)) {
                throw new OntologyException.SchemaViolation(
                        "'%s' alanı için geçersiz değer '%s'; izin verilenler: %s"
                                .formatted(property.apiName(), text, values));
            }
        }
        intConstraint(c, "minLength").ifPresent(min -> {
            if (text.length() < min) {
                throw new OntologyException.SchemaViolation(
                        "'%s' alanı en az %d karakter olmalı".formatted(property.apiName(), min));
            }
        });
        intConstraint(c, "maxLength").ifPresent(max -> {
            if (text.length() > max) {
                throw new OntologyException.SchemaViolation(
                        "'%s' alanı en fazla %d karakter olmalı".formatted(property.apiName(), max));
            }
        });
        if (c.get("pattern") instanceof String pattern) {
            try {
                if (!Pattern.matches(pattern, text)) {
                    throw new OntologyException.SchemaViolation(
                            "'%s' alanı '%s' desenine uymuyor".formatted(property.apiName(), pattern));
                }
            } catch (PatternSyntaxException e) {
                throw new OntologyException.SchemaViolation(
                        "'%s' alanının pattern kısıtı geçersiz: %s".formatted(property.apiName(), pattern));
            }
        }
    }

    private void validateNumeric(PropertyTypeDef property, BigDecimal value) {
        Map<String, Object> c = property.constraints();
        decimalConstraint(c, "min").ifPresent(min -> {
            if (value.compareTo(min) < 0) {
                throw new OntologyException.SchemaViolation(
                        "'%s' alanı %s değerinden küçük olamaz".formatted(property.apiName(), min));
            }
        });
        decimalConstraint(c, "max").ifPresent(max -> {
            if (value.compareTo(max) > 0) {
                throw new OntologyException.SchemaViolation(
                        "'%s' alanı %s değerinden büyük olamaz".formatted(property.apiName(), max));
            }
        });
    }

    private BigDecimal normalizeInteger(PropertyTypeDef property, BigDecimal value) {
        if (property.dataType() != DataType.INTEGER) {
            return value;
        }
        if (value.stripTrailingZeros().scale() > 0) {
            throw new OntologyException.SchemaViolation(
                    "'%s' alanı tam sayı; %s kabul edilmiyor".formatted(property.apiName(), value));
        }
        return value.stripTrailingZeros();
    }

    private void validateJson(PropertyTypeDef property, String json) {
        try {
            objectMapper.readTree(json);
        } catch (Exception e) {
            throw new OntologyException.SchemaViolation(
                    "'%s' alanına geçersiz JSON verildi".formatted(property.apiName()));
        }
    }

    private static java.util.Optional<Integer> intConstraint(Map<String, Object> c, String key) {
        Object raw = c.get(key);
        return raw instanceof Number n ? java.util.Optional.of(n.intValue()) : java.util.Optional.empty();
    }

    private static java.util.Optional<BigDecimal> decimalConstraint(Map<String, Object> c, String key) {
        Object raw = c.get(key);
        if (raw instanceof BigDecimal d) {
            return java.util.Optional.of(d);
        }
        if (raw instanceof Number n) {
            return java.util.Optional.of(new BigDecimal(n.toString()));
        }
        if (raw instanceof String s) {
            try {
                return java.util.Optional.of(new BigDecimal(s));
            } catch (NumberFormatException ignored) {
                return java.util.Optional.empty();
            }
        }
        return java.util.Optional.empty();
    }

    static List<String> valueColumnNames() {
        return List.of("value_text", "value_numeric", "value_bool", "value_ts", "value_json", "value_ref");
    }
}
