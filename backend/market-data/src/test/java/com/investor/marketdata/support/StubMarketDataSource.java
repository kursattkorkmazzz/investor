package com.investor.marketdata.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.investor.marketdata.MarketDataSource;
import com.investor.marketdata.model.Bar;
import com.investor.marketdata.model.InstrumentSpec;
import com.investor.marketdata.model.Timeframe;

/**
 * Borsa yerine geçen kaynak.
 *
 * <p>Deterministik mumlar üretir ve kaç istek yapıldığını sayar. Ingest'in sayfalama ve
 * boşluk doldurma davranışını gerçek ağ olmadan sınamak için.
 */
public class StubMarketDataSource implements MarketDataSource {

    private final Instant epoch;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int maxBarsPerRequest = 1000;
    private volatile Instant finalUntil = Instant.MAX;

    public StubMarketDataSource(Instant epoch) {
        this.epoch = epoch;
    }

    @Override
    public String exchangeName() {
        return "BINANCE";
    }

    @Override
    public List<InstrumentSpec> instruments() {
        return List.of();
    }

    @Override
    public List<Bar> klines(String symbol, Timeframe timeframe,
                            Instant fromInclusive, Instant toExclusive, int limit) {
        requests.incrementAndGet();
        List<Bar> bars = new ArrayList<>();
        Instant cursor = timeframe.floor(fromInclusive);
        int produced = 0;
        int cap = Math.min(limit <= 0 ? maxBarsPerRequest : limit, maxBarsPerRequest);

        while (cursor.isBefore(toExclusive) && produced < cap) {
            long index = java.time.Duration.between(epoch, cursor).dividedBy(timeframe.duration());
            if (index >= 0) {
                Bar template = BarFixtures.minuteBar(
                        new com.investor.marketdata.model.InstrumentRef(0L, null, "BINANCE", symbol),
                        epoch, (int) index);
                bars.add(new Bar(0L, timeframe, cursor, timeframe.closeTime(cursor),
                        template.open(), template.high(), template.low(), template.close(),
                        template.volume(), template.quoteVolume(), template.tradeCount(),
                        template.takerBuyBase(), cursor.isBefore(finalUntil)));
            }
            cursor = cursor.plus(timeframe.duration());
            produced++;
        }
        return List.copyOf(bars);
    }

    @Override
    public int maxBarsPerRequest() {
        return maxBarsPerRequest;
    }

    public void setMaxBarsPerRequest(int value) {
        this.maxBarsPerRequest = value;
    }

    /** Bu andan sonraki mumlar kapanmamış sayılır. */
    public void setFinalUntil(Instant instant) {
        this.finalUntil = instant;
    }

    public int requestCount() {
        return requests.get();
    }

    public void resetRequestCount() {
        requests.set(0);
    }
}
