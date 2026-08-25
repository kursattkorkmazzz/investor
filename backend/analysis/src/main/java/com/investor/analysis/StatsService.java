package com.investor.analysis;

import java.time.Instant;

import com.investor.analysis.model.PriceStats;
import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.Timeframe;

/**
 * Fiyat ve hacim istatistikleri — deterministik.
 *
 * <p>{@link IndicatorService} gibi {@code asOf} zorunlu ve aynı girdiden aynı çıktı
 * garantisi var. Persentil hesabı geleceğe uzansaydı, geri testte "bu değer tarihsel
 * olarak düşüktü" cümlesi henüz yaşanmamış günleri de kapsardı — en sinsi look-ahead
 * biçimlerinden biri.
 */
public interface StatsService {

    PriceStats compute(InstrumentRef instrument, Timeframe timeframe, Instant asOf);
}
