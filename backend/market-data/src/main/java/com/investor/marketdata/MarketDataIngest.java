package com.investor.marketdata;

import java.time.Instant;

import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.Timeframe;

/** Veri toplama işlemlerinin tetiklenebilir yüzü. */
public interface MarketDataIngest {

    /**
     * Aralığı borsadan çekip yazar. Idempotenttir: aynı aralık tekrar çekilebilir.
     *
     * @return yazılan mum sayısı
     */
    int backfill(InstrumentRef instrument, Timeframe timeframe, Instant fromInclusive, Instant toExclusive);

    /** Watermark'tan bugüne kadar eksik olanı çeker. */
    int syncRecent(InstrumentRef instrument, Timeframe timeframe, Instant asOf);

    /** Tespit edilen boşlukları doldurur. */
    int fillGaps(InstrumentRef instrument, Timeframe timeframe, Instant fromInclusive, Instant toExclusive);

    /**
     * Taban mumlardan üst zaman dilimlerini türetir.
     *
     * <p>Eksik taban mumu olan aralıklar <em>atlanır</em> — yarım veriden üretilmiş bir
     * mum, yanlış olduğu anlaşılmayan bir indikatör üretir.
     *
     * @return yazılan mum sayısı
     */
    int rollup(InstrumentRef instrument, Timeframe target, Instant fromInclusive, Instant toExclusive);
}
