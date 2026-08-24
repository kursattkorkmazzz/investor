package com.investor.ontology.model;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Bir ilişkinin kendi taşıdığı bilgi.
 *
 * @param weight korelasyon katsayısı, etki gücü gibi ölçülebilir bağ kuvveti
 */
public record LinkProperties(BigDecimal weight, Map<String, Object> properties) {

    private static final LinkProperties EMPTY = new LinkProperties(null, Map.of());

    public static LinkProperties none() {
        return EMPTY;
    }

    public static LinkProperties weighted(BigDecimal weight) {
        return new LinkProperties(weight, Map.of());
    }
}
