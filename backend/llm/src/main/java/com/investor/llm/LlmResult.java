package com.investor.llm;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Doğrulanmış model cevabı.
 *
 * <p>{@code values} şemaya göre <em>zaten doğrulanmış</em> değerleri taşır: sayısal alanlar
 * sınırların içinde, enum alanları kapalı kümede, zorunlu alanlar mevcut. Çağıran tarafın
 * JSON ayrıştırması ya da aralık kontrolü yapması gerekmez — bu işi tek yerde yapmak,
 * her ajanın kendi gevşek kontrolünü yazmasından güvenli.
 *
 * <p>{@code rawJson} denetim için saklanır: modelin ne dediğini sonradan tartışabilmek,
 * karar motorunun "neden böyle karar verildi" sorusuna cevap verebilmesinin ön koşulu.
 */
public record LlmResult(
        UUID callId,
        String modelName,
        Map<String, Object> values,
        String rawJson,
        LlmUsage usage,
        Duration latency,
        boolean truncated) {

    public LlmResult {
        Objects.requireNonNull(callId, "çağrı kimliği zorunlu");
        values = values == null ? Map.of() : Map.copyOf(values);
        usage = usage == null ? LlmUsage.NONE : usage;
        latency = latency == null ? Duration.ZERO : latency;
    }

    public String string(String key) {
        Object v = require(key);
        return v.toString();
    }

    public double number(String key) {
        Object v = require(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalStateException("alan sayısal değil: " + key + " = " + v);
    }

    public boolean bool(String key) {
        Object v = require(key);
        if (v instanceof Boolean b) {
            return b;
        }
        throw new IllegalStateException("alan mantıksal değil: " + key + " = " + v);
    }

    @SuppressWarnings("unchecked")
    public List<String> stringList(String key) {
        Object v = values.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> l) {
            return (List<String>) l;
        }
        throw new IllegalStateException("alan liste değil: " + key + " = " + v);
    }

    /**
     * Enum alanını Java enum'una çevirir. Model kapalı kümenin dışına çıkarsa
     * {@code fallback} döner — şema zorlamasına rağmen bunun olabildiğini varsayıyoruz,
     * çünkü katı şema desteklemeyen uç noktalarda çıktı yalnızca istemle yönlendiriliyor.
     */
    public <E extends Enum<E>> E enumValue(String key, Class<E> type, E fallback) {
        Object v = values.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, v.toString().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    private Object require(String key) {
        Object v = values.get(key);
        if (v == null) {
            throw new IllegalStateException("cevapta beklenen alan yok: " + key);
        }
        return v;
    }
}
