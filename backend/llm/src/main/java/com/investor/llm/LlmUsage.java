package com.investor.llm;

import java.math.BigDecimal;

/**
 * Bir çağrının token tüketimi.
 *
 * <p>{@code cachedInputTokens} ayrı tutuluyor çünkü fiyatı farklı: önbelleğe düşen girdi
 * tipik olarak tam fiyatın küçük bir kesri. Analiz ajanları aynı sistem istemini defalarca
 * gönderecek — bu kalem, maliyet tahminini gerçeğe yaklaştıran şey.
 *
 * <p>{@code reasoningTokens} çıktı tokenlarının içinde sayılır ama görünmez; bir modelin
 * neden beklenenden pahalı olduğunu açıklayan tek kalem çoğu zaman bu.
 */
public record LlmUsage(
        int inputTokens,
        int cachedInputTokens,
        int outputTokens,
        int reasoningTokens) {

    public static final LlmUsage NONE = new LlmUsage(0, 0, 0, 0);

    public LlmUsage {
        if (inputTokens < 0 || outputTokens < 0 || cachedInputTokens < 0 || reasoningTokens < 0) {
            throw new IllegalArgumentException("token sayıları negatif olamaz");
        }
        if (cachedInputTokens > inputTokens) {
            throw new IllegalArgumentException(
                    "önbellekli girdi toplam girdiyi aşamaz: " + cachedInputTokens + " > " + inputTokens);
        }
    }

    public int totalTokens() {
        return inputTokens + outputTokens;
    }

    /** Önbelleğe düşmemiş, tam fiyatlı girdi. */
    public int freshInputTokens() {
        return inputTokens - cachedInputTokens;
    }

    /**
     * Milyon token başına fiyatlarla maliyet.
     *
     * @param inputPerMillion  taze girdi fiyatı
     * @param cachedPerMillion önbellekli girdi fiyatı
     * @param outputPerMillion çıktı fiyatı
     */
    public BigDecimal cost(BigDecimal inputPerMillion, BigDecimal cachedPerMillion,
                           BigDecimal outputPerMillion) {
        BigDecimal million = new BigDecimal("1000000");
        return inputPerMillion.multiply(BigDecimal.valueOf(freshInputTokens()))
                .add(cachedPerMillion.multiply(BigDecimal.valueOf(cachedInputTokens)))
                .add(outputPerMillion.multiply(BigDecimal.valueOf(outputTokens)))
                .divide(million, 8, java.math.RoundingMode.HALF_UP);
    }
}
