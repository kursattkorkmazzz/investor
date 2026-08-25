package com.investor.marketdata.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.investor.marketdata.model.Bar;
import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.Timeframe;

/**
 * Deterministik mum üretimi.
 *
 * <p>Değerler indeksten hesaplanıyor, rastgele değil: beklenen rollup sonucu testte
 * bağımsız olarak hesaplanabilsin. Rastgele veri, rollup'ın doğruluğunu değil yalnızca
 * "patlamıyor"u test ederdi.
 */
public final class BarFixtures {

    private BarFixtures() {
    }

    /** {@code index}'e göre öngörülebilir bir 1m mum. */
    public static Bar minuteBar(InstrumentRef instrument, Instant start, int index) {
        Instant openTime = start.plus(Timeframe.M1.duration().multipliedBy(index));
        BigDecimal base = BigDecimal.valueOf(100 + index);
        return new Bar(
                instrument.id(),
                Timeframe.M1,
                openTime,
                Timeframe.M1.closeTime(openTime),
                base,                                   // open
                base.add(BigDecimal.valueOf(2)),        // high
                base.subtract(BigDecimal.ONE),          // low
                base.add(BigDecimal.ONE),               // close
                BigDecimal.valueOf(10 + index),         // volume
                BigDecimal.valueOf((100L + index) * (10L + index)), // quote volume
                5 + index,                              // trade count
                BigDecimal.valueOf(10 + index).divide(BigDecimal.TWO), // taker buy
                true);
    }

    public static List<Bar> minuteBars(InstrumentRef instrument, Instant start, int count) {
        List<Bar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            bars.add(minuteBar(instrument, start, i));
        }
        return List.copyOf(bars);
    }

    /**
     * Verilen mumlardan beklenen birleşik mumu <em>bağımsız olarak</em> hesaplar.
     *
     * <p>SQL'in yaptığı işi Java'da tekrar yapmak kasıtlı: iki bağımsız gerçekleme aynı
     * sonucu veriyorsa, ikisinin de aynı hatayı yapma olasılığı düşük.
     */
    public static Bar expectedAggregate(List<Bar> sources, Timeframe target, Instant bucketStart) {
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("kaynak mum yok");
        }
        Bar first = sources.get(0);
        Bar last = sources.get(sources.size() - 1);

        BigDecimal high = sources.get(0).high();
        BigDecimal low = sources.get(0).low();
        BigDecimal volume = BigDecimal.ZERO;
        BigDecimal quoteVolume = BigDecimal.ZERO;
        BigDecimal takerBuy = BigDecimal.ZERO;
        int trades = 0;

        for (Bar bar : sources) {
            high = high.max(bar.high());
            low = low.min(bar.low());
            volume = volume.add(bar.volume());
            quoteVolume = quoteVolume.add(bar.quoteVolume());
            takerBuy = takerBuy.add(bar.takerBuyBase());
            trades += bar.tradeCount();
        }

        return new Bar(first.instrumentId(), target, bucketStart, target.closeTime(bucketStart),
                first.open(), high, low, last.close(),
                volume, quoteVolume, trades, takerBuy, true);
    }
}
