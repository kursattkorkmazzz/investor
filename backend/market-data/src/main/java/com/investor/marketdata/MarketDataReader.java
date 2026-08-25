package com.investor.marketdata;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.investor.marketdata.model.Bar;
import com.investor.marketdata.model.Freshness;
import com.investor.marketdata.model.Gap;
import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.Timeframe;

/**
 * Karar üretiminin tek piyasa verisi okuma yolu.
 *
 * <h2>Neden buradan kapanmamış mum okunamıyor</h2>
 * Kapanmamış bir mumdan hesaplanan indikatör, mum kapanınca değişir. Backtest'te bu
 * "geleceği görmek" demektir ve sonucu gerçekte olduğundan iyi gösterir — üstelik
 * hiçbir aşamada hata olarak görünmez. Bu arayüz yalnızca {@code is_final} mumları
 * döner; canlı mum ayrı bir arayüzde yaşar ve analiz modülüne kapalıdır.
 *
 * <h2>Neden her metot zaman sınırı istiyor</h2>
 * "Son 200 mumu ver" diyen bir imza, backtest sırasında sessizce geleceğe uzanır.
 * Sınır isteğe bağlı olsaydı unutulurdu; zorunlu olduğu için unutulamaz.
 */
public interface MarketDataReader {

    /**
     * Verilen aralıktaki kapanmış mumlar, açılış zamanına göre artan sırada.
     *
     * @param toExclusive üst sınır dahil değildir
     */
    List<Bar> finalBars(InstrumentRef instrument, Timeframe timeframe,
                        Instant fromInclusive, Instant toExclusive);

    /**
     * {@code asOf} anında kapanmış olan son {@code count} mum, artan sırada.
     *
     * <p>{@code asOf} anında hâlâ açık olan mum sonuca girmez.
     */
    List<Bar> lastFinalBars(InstrumentRef instrument, Timeframe timeframe, int count, Instant asOf);

    Optional<Bar> finalBarAt(InstrumentRef instrument, Timeframe timeframe, Instant openTime);

    /** {@code asOf} anına kadar kapanmış son mumun açılış zamanı. */
    Optional<Instant> lastFinalOpenTime(InstrumentRef instrument, Timeframe timeframe, Instant asOf);

    /**
     * Aralıktaki eksik mumlar.
     *
     * <p>Borsa bakımları ve WebSocket kopmaları boşluk üretir. Eksik 1m mumundan
     * üretilen bir 15m mum, doğru görünen yanlış bir indikatör demektir.
     */
    List<Gap> findGaps(InstrumentRef instrument, Timeframe timeframe,
                       Instant fromInclusive, Instant toExclusive);

    /** Verinin {@code asOf} anındaki tazeliği. Ajanların çekimser kalma kararı buna dayanır. */
    Freshness freshness(InstrumentRef instrument, Timeframe timeframe, Instant asOf);
}
