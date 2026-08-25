package com.investor.llm.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.investor.llm.LlmException;
import com.investor.llm.OutputSchema;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Model cevabını şemaya göre ayrıştırır ve doğrular.
 *
 * <p>Bu, istem enjeksiyonuna karşı <em>asıl</em> savunma katmanı. Zarf saldırıyı zorlaştırır;
 * burası saldırının etkisini sınırlar. Model ne söylerse söylesin:
 * <ul>
 *   <li>şemada olmayan alan atılır — yeni bir eylem kanalı açılamaz,</li>
 *   <li>sayısal alan sınırların dışındaysa kırpılır — "materiality: 999" 1.0 olur,</li>
 *   <li>enum alanı kapalı kümenin dışındaysa alan düşürülür,</li>
 *   <li>zorunlu alan eksikse çağrı başarısız sayılır.</li>
 * </ul>
 *
 * <p>Kırpma yerine reddetme de düşünülebilirdi. Kırpmayı seçtik çünkü sınır ihlali çoğu zaman
 * saldırı değil, modelin ölçeği yanlış anlaması; her seferinde reddetmek hattı gereksiz yere
 * durdururdu. Ama kırpma sessiz değil: {@link #clamped()} sayacı ölçüme gidiyor ve bu sayının
 * artması modelin ya da istemin bozulduğunun erken işareti.
 */
final class ResponseValidator {

    private final JsonMapper mapper;
    private final List<String> clamped = new ArrayList<>();

    ResponseValidator(JsonMapper mapper) {
        this.mapper = mapper;
    }

    List<String> clamped() {
        return List.copyOf(clamped);
    }

    Map<String, Object> validate(String raw, OutputSchema schema) {
        JsonNode root = parse(raw);
        if (!root.isObject()) {
            throw new LlmException("model JSON nesnesi döndürmedi: " + preview(raw), true);
        }

        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, OutputSchema.Field> entry : schema.fields().entrySet()) {
            String key = entry.getKey();
            JsonNode node = root.get(key);
            if (node == null || node.isNull()) {
                continue;
            }
            Object value = convert(key, node, entry.getValue());
            if (value != null) {
                values.put(key, value);
            }
        }

        List<String> missing = schema.required().stream().filter(k -> !values.containsKey(k)).toList();
        if (!missing.isEmpty()) {
            throw new LlmException("cevapta zorunlu alanlar eksik: " + missing, true);
        }
        return values;
    }

    private JsonNode parse(String raw) {
        try {
            return mapper.readTree(JsonExtractor.extract(raw));
        } catch (JacksonException e) {
            throw new LlmException("model cevabı geçerli JSON değil: " + preview(raw), e, true);
        }
    }

    private Object convert(String key, JsonNode node, OutputSchema.Field field) {
        return switch (field.kind()) {
            case STRING -> node.isValueNode() ? node.asString() : node.toString();
            case BOOLEAN -> toBoolean(node);
            case INTEGER -> {
                Double d = toNumber(key, node, field);
                yield d == null ? null : (long) Math.rint(d);
            }
            case NUMBER -> toNumber(key, node, field);
            case ENUM -> toEnum(node, field);
            case STRING_ARRAY -> toStringList(node);
        };
    }

    private Boolean toBoolean(JsonNode node) {
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        // Katı şema desteklemeyen uç noktalarda "true"/"false" metin olarak gelebiliyor.
        String text = node.asString().trim().toLowerCase(Locale.ROOT);
        if (text.equals("true") || text.equals("false")) {
            return Boolean.valueOf(text);
        }
        return null;
    }

    private Double toNumber(String key, JsonNode node, OutputSchema.Field field) {
        double value;
        if (node.isNumber()) {
            value = node.doubleValue();
        } else {
            try {
                value = Double.parseDouble(node.asString().trim());
            } catch (NumberFormatException | UnsupportedOperationException e) {
                return null;
            }
        }
        if (!Double.isFinite(value)) {
            return null;
        }
        Double min = field.min();
        Double max = field.max();
        if (min != null && value < min) {
            clamped.add(key);
            return min;
        }
        if (max != null && value > max) {
            clamped.add(key);
            return max;
        }
        return value;
    }

    private String toEnum(JsonNode node, OutputSchema.Field field) {
        String text = node.asString().trim().toUpperCase(Locale.ROOT);
        return field.enumValues().stream()
                .filter(v -> v.equalsIgnoreCase(text))
                .findFirst()
                .orElse(null);
    }

    private List<String> toStringList(JsonNode node) {
        if (!node.isArray()) {
            // Tek bir değer dizi yerine düz gelirse kabul et; bu, modelin sık yaptığı
            // ve anlamı bozmayan bir hata.
            return node.isValueNode() ? List.of(node.asString()) : List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(child -> {
            if (child.isValueNode() && !child.isNull()) {
                String text = child.asString().trim();
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
        });
        return List.copyOf(out);
    }

    private static String preview(String raw) {
        if (raw == null) {
            return "<boş>";
        }
        String trimmed = raw.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "…";
    }
}
