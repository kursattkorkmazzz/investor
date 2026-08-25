package com.investor.marketdata.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Tek bir OHLCV mumu.
 *
 * <p>Tüm fiyat ve miktarlar {@link BigDecimal}: {@code double} ile taşınan bir fiyat,
 * borsanın reddettiği bir emre ve yanlış hesaplanmış bir PnL'e dönüşür.
 *
 * @param isFinal mum kapandı mı. {@code false} olanlar karar üretiminde kullanılamaz —
 *                kapanmamış mumdan hesaplanan indikatör mum kapanınca değişir ve bu,
 *                backtest'te "geleceği görmek" demektir.
 */
public record Bar(
        long instrumentId,
        Timeframe timeframe,
        Instant openTime,
        Instant closeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        BigDecimal quoteVolume,
        int tradeCount,
        BigDecimal takerBuyBase,
        boolean isFinal) {

    public Bar {
        if (openTime == null || closeTime == null) {
            throw new IllegalArgumentException("mum zamanları zorunlu");
        }
        if (!closeTime.isAfter(openTime)) {
            throw new IllegalArgumentException("kapanış zamanı açılıştan sonra olmalı");
        }
        if (high.compareTo(low) < 0) {
            throw new IllegalArgumentException(
                    "yüksek (%s) düşükten (%s) küçük olamaz".formatted(high, low));
        }
    }

    /** Mumun gövde yönü. Doji (open == close) {@code false} sayılır. */
    public boolean isBullish() {
        return close.compareTo(open) > 0;
    }

    public BigDecimal range() {
        return high.subtract(low);
    }

    /** Ortalama işlem fiyatı; hacim sıfırsa kapanışa düşer. */
    public BigDecimal vwapApprox() {
        return volume.signum() == 0
                ? close
                : quoteVolume.divide(volume, 12, java.math.RoundingMode.HALF_UP);
    }
}
