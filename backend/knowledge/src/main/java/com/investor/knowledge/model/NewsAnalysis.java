package com.investor.knowledge.model;

import java.util.List;

/**
 * Bir haberin çıkarım sonucu.
 *
 * @param sentiment   yön: -1 (çok olumsuz) … +1 (çok olumlu)
 * @param materiality önem: 0 … 1
 * @param entities    haberin ilgilendirdiği varlıklar (ontoloji external_id'leri)
 *
 * <p>{@code sentiment} ve {@code materiality} bilinçli olarak ayrı. "Çok olumsuz ama
 * önemsiz" bir haber ile "hafif olumsuz ama çok önemli" bir haber farklı ağırlık
 * taşımalı; tek bir skora sıkıştırıldıklarında bu ayrım kaybolur.
 */
public record NewsAnalysis(
        double sentiment,
        double materiality,
        EventType eventType,
        List<String> entities,
        String summary,
        boolean speculation,
        TimeHorizon timeHorizon) {

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
    }

    public static NewsAnalysis neutral(String summary) {
        return new NewsAnalysis(0, 0.1, EventType.OTHER, List.of(), summary, false, TimeHorizon.DAYS);
    }
}
