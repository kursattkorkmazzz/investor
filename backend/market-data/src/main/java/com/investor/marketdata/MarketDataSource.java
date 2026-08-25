package com.investor.marketdata;

import java.time.Instant;
import java.util.List;

import com.investor.marketdata.model.Bar;
import com.investor.marketdata.model.InstrumentSpec;
import com.investor.marketdata.model.Timeframe;

/**
 * Bir borsadan piyasa verisi çeken kaynak.
 *
 * <p>Emir gönderme yetkisi <em>yoktur</em>: veri toplama ile işlem yapma ayrı portlar.
 * Veri toplayan bileşenin işlem yetkisi olmaması, hem yetki yüzeyini daraltır hem de
 * ingest'in salt-okunur anahtarla koşturulabilmesini sağlar.
 */
public interface MarketDataSource {

    String exchangeName();

    /** Borsanın bildirdiği tüm enstrümanlar. */
    List<InstrumentSpec> instruments();

    /**
     * Mum verisi çeker.
     *
     * <p>Dönen listede kapanmamış mum bulunabilir; {@link Bar#isFinal()} ayırt eder.
     * Kaynak, kapanmamış mumu gizlemez — gizleseydi canlı takip imkânsız olurdu;
     * ayrım okuma katmanında yapılır.
     *
     * @param limit istenen azami mum sayısı; {@link #maxBarsPerRequest()} ile sınırlıdır
     */
    List<Bar> klines(String symbol, Timeframe timeframe,
                     Instant fromInclusive, Instant toExclusive, int limit);

    int maxBarsPerRequest();
}
