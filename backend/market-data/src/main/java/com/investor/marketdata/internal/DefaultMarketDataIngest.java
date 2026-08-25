package com.investor.marketdata.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.investor.marketdata.MarketDataIngest;
import com.investor.marketdata.MarketDataReader;
import com.investor.marketdata.MarketDataSource;
import com.investor.marketdata.model.Bar;
import com.investor.marketdata.model.Gap;
import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.Timeframe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Veri toplama akışı.
 *
 * <p>Tüm yazma yolları idempotent: aynı aralık defalarca çekilebilir, sonuç değişmez.
 * Ağ hatasından sonra yeniden denemek normal işleyiştir; "acaba iki kez mi yazdım"
 * sorusunun hiç sorulmaması gerekir.
 */
class DefaultMarketDataIngest implements MarketDataIngest {

    private static final Logger log = LoggerFactory.getLogger(DefaultMarketDataIngest.class);

    /** Bir çağrıda yapılacak azami istek — sonsuz döngüye karşı sert tavan. */
    private static final int MAX_REQUESTS_PER_CALL = 500;

    private final MarketDataSource source;
    private final MarketDataReader reader;
    private final BarWriter writer;
    private final RollupService rollupService;
    private final IngestWatermarks watermarks;
    private final Clock clock;

    DefaultMarketDataIngest(MarketDataSource source, MarketDataReader reader, BarWriter writer,
                            RollupService rollupService, IngestWatermarks watermarks, Clock clock) {
        this.source = source;
        this.reader = reader;
        this.writer = writer;
        this.rollupService = rollupService;
        this.watermarks = watermarks;
        this.clock = clock;
    }

    @Override
    public int backfill(InstrumentRef instrument, Timeframe timeframe,
                        Instant fromInclusive, Instant toExclusive) {
        Instant cursor = timeframe.floor(fromInclusive);
        Instant end = timeframe.floor(toExclusive);
        if (!end.isAfter(cursor)) {
            return 0;
        }

        int total = 0;
        int requests = 0;
        Instant lastFinalOpen = null;

        try {
            while (cursor.isBefore(end) && requests < MAX_REQUESTS_PER_CALL) {
                requests++;
                List<Bar> fetched = source.klines(instrument.symbol(), timeframe,
                        cursor, end, source.maxBarsPerRequest());
                if (fetched.isEmpty()) {
                    break;
                }

                List<Bar> owned = new ArrayList<>(fetched.size());
                Instant maxOpen = cursor;
                for (Bar bar : fetched) {
                    owned.add(withInstrument(bar, instrument));
                    if (bar.openTime().isAfter(maxOpen)) {
                        maxOpen = bar.openTime();
                    }
                    if (bar.isFinal() && (lastFinalOpen == null || bar.openTime().isAfter(lastFinalOpen))) {
                        lastFinalOpen = bar.openTime();
                    }
                }
                total += writer.write(owned);

                Instant next = maxOpen.plus(timeframe.duration());
                if (!next.isAfter(cursor)) {
                    // Kaynak ilerlemiyor: aynı mumu tekrar tekrar döndürüyor olabilir.
                    log.warn("{} {} ingest ilerlemedi ({}); durduruldu",
                            instrument.qualifiedSymbol(), timeframe.code(), cursor);
                    break;
                }
                cursor = next;
            }

            watermarks.recordSuccess(instrument, timeframe, lastFinalOpen, clock.instant());
            return total;
        } catch (RuntimeException e) {
            watermarks.recordFailure(instrument, timeframe, clock.instant(), e.getMessage());
            throw e;
        }
    }

    @Override
    public int syncRecent(InstrumentRef instrument, Timeframe timeframe, Instant asOf) {
        Instant from = watermarks.lastFinalOpen(instrument, timeframe)
                .map(last -> last.plus(timeframe.duration()))
                // İlk çalıştırma: son 200 mumdan başla; boş bir tablodan tüm tarihi çekmeye kalkma.
                .orElseGet(() -> timeframe.floor(asOf).minus(timeframe.duration().multipliedBy(200)));

        Instant to = timeframe.floor(asOf).plus(timeframe.duration());
        return to.isAfter(from) ? backfill(instrument, timeframe, from, to) : 0;
    }

    @Override
    public int fillGaps(InstrumentRef instrument, Timeframe timeframe,
                        Instant fromInclusive, Instant toExclusive) {
        List<Gap> gaps = reader.findGaps(instrument, timeframe, fromInclusive, toExclusive);
        if (gaps.isEmpty()) {
            return 0;
        }
        log.info("{} {} için {} boşluk bulundu, dolduruluyor",
                instrument.qualifiedSymbol(), timeframe.code(), gaps.size());

        int filled = 0;
        for (Gap gap : gaps) {
            filled += backfill(instrument, timeframe, gap.fromInclusive(), gap.toExclusive());
        }
        return filled;
    }

    @Override
    public int rollup(InstrumentRef instrument, Timeframe target, Instant fromInclusive, Instant toExclusive) {
        return rollupService.rollup(instrument, target, fromInclusive, toExclusive);
    }

    private static Bar withInstrument(Bar bar, InstrumentRef instrument) {
        return new Bar(instrument.id(), bar.timeframe(), bar.openTime(), bar.closeTime(),
                bar.open(), bar.high(), bar.low(), bar.close(),
                bar.volume(), bar.quoteVolume(), bar.tradeCount(), bar.takerBuyBase(), bar.isFinal());
    }
}
