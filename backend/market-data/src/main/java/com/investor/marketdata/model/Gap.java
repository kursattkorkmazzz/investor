package com.investor.marketdata.model;

import java.time.Instant;

/**
 * Veri boşluğu: beklenen ama bulunamayan mum aralığı.
 *
 * <p>Boşluklar sessiz kalamaz. Eksik 1m mumundan üretilmiş bir 15m mum, doğru görünen
 * ama yanlış olan bir indikatör üretir — ve bu hata hiçbir yerde hata olarak görünmez.
 *
 * @param toExclusive boşluğun bittiği (dahil olmayan) an
 */
public record Gap(Timeframe timeframe, Instant fromInclusive, Instant toExclusive, long missingBars) {

    public java.time.Duration length() {
        return java.time.Duration.between(fromInclusive, toExclusive);
    }
}
