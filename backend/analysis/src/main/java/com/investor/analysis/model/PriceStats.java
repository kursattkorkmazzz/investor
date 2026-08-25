package com.investor.analysis.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.Timeframe;

/**
 * {@code asOf} anındaki fiyat ve hacim istatistikleri.
 *
 * <p>Göstergeler "ne oldu" der; bu küme "olağan mı" der. LLM'e {@code rsi14 = 28.4}
 * vermek tek başına yetersiz — 28.4'ün son üç ayın 5. persentili mi yoksa 40.
 * persentili mi olduğu, aynı sayıyı iki farklı kanıta çeviriyor.
 *
 * <p>{@link IndicatorSet} gibi burada da eksik değer sessizce sıfır olmuyor: hesaplanamayan
 * istatistik kümede yer almıyor.
 */
public record PriceStats(
        InstrumentRef instrument,
        Timeframe timeframe,
        Instant asOf,
        int barsAvailable,
        Map<String, StatValue> values) {

    public PriceStats {
        values = values == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(values));
    }

    public static PriceStats empty(InstrumentRef instrument, Timeframe timeframe, Instant asOf) {
        return new PriceStats(instrument, timeframe, asOf, 0, Map.of());
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public Optional<StatValue> get(String name) {
        return Optional.ofNullable(values.get(name));
    }

    public Optional<BigDecimal> value(String name) {
        return get(name).map(StatValue::value);
    }

    public double doubleOr(String name, double fallback) {
        return get(name).map(StatValue::doubleValue).orElse(fallback);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
