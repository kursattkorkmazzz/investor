package com.investor.knowledge.model;

import java.util.List;

/**
 * Bir haberin çıkarım sonucu.
 *
 * <p>{@code sentiment} ve {@code materiality} bilinçli olarak ayrı. "Çok olumsuz ama
 * önemsiz" bir haber ile "hafif olumsuz ama çok önemli" bir haber farklı ağırlık
 * taşımalı; tek bir skora sıkıştırıldıklarında bu ayrım kaybolur.
 *
 * <p>{@code extractorId} sonucun <em>üzerinde</em> taşınıyor, çıkarıcının üzerinde değil.
 * Sebep: bir çıkarıcı başarısız olup yedeğine düşebiliyor. Kimlik yalnızca portta dursaydı
 * ontolojiye "LLM çıkardı" yazılır, gerçekte kural tabanlı yedek çalışmış olurdu. Sonradan
 * "LLM'in duygu skorları kural tabanlıdan iyi miydi" sorusu sorulacak; bu soru ancak her
 * satırın hangi çıkarıcıdan geldiği doğruysa cevaplanabilir.
 *
 * @param sentiment   yön: -1 (çok olumsuz) … +1 (çok olumlu)
 * @param materiality önem: 0 … 1
 * @param entities    haberin ilgilendirdiği varlıklar (ontoloji external_id'leri)
 * @param extractorId çıkarımı <em>gerçekten</em> yapan bileşen
 */
public record NewsAnalysis(
        double sentiment,
        double materiality,
        EventType eventType,
        List<String> entities,
        String summary,
        boolean speculation,
        TimeHorizon timeHorizon,
        String extractorId) {

    public enum TimeHorizon {
        HOURS,
        DAYS,
        WEEKS,
        MONTHS
    }

    public NewsAnalysis {
        if (sentiment < -1 || sentiment > 1) {
            throw new IllegalArgumentException("duygu -1 ile 1 arasında olmalı: " + sentiment);
        }
        if (materiality < 0 || materiality > 1) {
            throw new IllegalArgumentException("önem 0 ile 1 arasında olmalı: " + materiality);
        }
        entities = entities == null ? List.of() : List.copyOf(entities);
        eventType = eventType == null ? EventType.OTHER : eventType;
        timeHorizon = timeHorizon == null ? TimeHorizon.DAYS : timeHorizon;
        extractorId = extractorId == null || extractorId.isBlank() ? "unknown" : extractorId;
    }

    /**
     * Çıkarım yapılamadığında dönülecek nötr sonuç.
     *
     * <p>Önem 0.1: sıfır değil, çünkü haberin var olduğu bilgisi kendi başına küçük bir
     * sinyal. Ama düşük, çünkü içeriği hakkında hiçbir şey bilmiyoruz.
     */
    public static NewsAnalysis neutral(String summary, String extractorId) {
        return new NewsAnalysis(0, 0.1, EventType.OTHER, List.of(), summary, false,
                TimeHorizon.DAYS, extractorId);
    }

    /** Aynı çıkarımı başka bir çıkarıcı kimliğiyle etiketler (yedeğe düşüldüğünde). */
    public NewsAnalysis withExtractorId(String id) {
        return new NewsAnalysis(sentiment, materiality, eventType, entities, summary, speculation,
                timeHorizon, id);
    }
}
