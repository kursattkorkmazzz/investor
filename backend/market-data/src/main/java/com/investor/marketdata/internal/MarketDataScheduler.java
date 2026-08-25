package com.investor.marketdata.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.investor.marketdata.InstrumentCatalog;
import com.investor.marketdata.MarketDataIngest;
import com.investor.marketdata.MarketDataSource;
import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.InstrumentSpec;
import com.investor.marketdata.model.Timeframe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Zamanlanmış veri toplama.
 *
 * <h2>Çakışma koruması</h2>
 * Her iş, kendi önceki koşusu bitmeden yeniden başlamaz. Tek instance'ta gerçek risk
 * budur: yavaş bir backfill'in üstüne ikincisinin binmesi. Birden fazla instance'a
 * geçildiğinde bu yetmez — o noktada ShedLock gibi bir dağıtık kilit gerekir.
 *
 * <h2>Hata politikası</h2>
 * Bir sembolün hatası diğerlerini durdurmaz; her biri ayrı yakalanır. Ingest'in tamamen
 * susması, tek bir sembolün eksik kalmasından kötüdür — ama sessizce susması en kötüsü,
 * bu yüzden hatalar hem loglanır hem watermark'a yazılır.
 */
class MarketDataScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketDataScheduler.class);

    private final InstrumentCatalog catalog;
    private final MarketDataIngest ingest;
    private final MarketDataSource source;
    private final PartitionMaintenance partitions;
    private final MarketDataProperties properties;
    private final Clock clock;

    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();

    MarketDataScheduler(InstrumentCatalog catalog, MarketDataIngest ingest, MarketDataSource source,
                        PartitionMaintenance partitions, MarketDataProperties properties, Clock clock) {
        this.catalog = catalog;
        this.ingest = ingest;
        this.source = source;
        this.partitions = partitions;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Yapılandırmadaki sembolleri borsadan alıp kaydeder.
     *
     * <p>Beyaz liste yapılandırmada: sistemin hangi enstrümanlara dokunabileceği koda
     * değil ayara bağlı, ve borsadaki her sembolü çekmiyoruz.
     */
    @EventListener(ApplicationReadyEvent.class)
    void registerConfiguredInstruments() {
        if (properties.symbols().isEmpty()) {
            log.info("Yapılandırmada sembol yok; piyasa verisi toplama boşta.");
            return;
        }
        guard("bootstrap", () -> {
            List<InstrumentSpec> available = source.instruments();
            for (String symbol : properties.symbols()) {
                available.stream()
                        .filter(spec -> spec.symbol().equalsIgnoreCase(symbol))
                        .findFirst()
                        .ifPresentOrElse(
                                spec -> {
                                    catalog.register(spec);
                                    log.info("Enstrüman kaydedildi: {}:{}", spec.exchange(), spec.symbol());
                                },
                                () -> log.warn("Sembol borsada bulunamadı: {}", symbol));
            }
        });
    }

    /** Taban mumları tazele ve üst dilimleri türet. */
    @Scheduled(fixedDelayString = "${investor.market-data.sync-interval:PT1M}")
    void syncAndRollup() {
        guard("sync", () -> {
            Instant now = clock.instant();
            for (InstrumentRef instrument : catalog.all()) {
                try {
                    int bars = ingest.syncRecent(instrument, Timeframe.BASE, now);
                    if (bars > 0) {
                        rollupDerived(instrument, now);
                    }
                } catch (RuntimeException e) {
                    log.warn("{} senkronizasyonu başarısız: {}",
                            instrument.qualifiedSymbol(), e.getMessage());
                }
            }
        });
    }

    /**
     * Son bir günün boşluklarını doldur.
     *
     * <p>WebSocket kopmaları, borsa bakımları ve deploy'lar boşluk üretir; kapatılmazsa
     * rollup o kovaları atlamaya devam eder ve üst dilimlerde kalıcı delik kalır.
     */
    @Scheduled(fixedDelayString = "${investor.market-data.gap-scan-interval:PT5M}")
    void fillRecentGaps() {
        guard("gap-scan", () -> {
            Instant now = clock.instant();
            Instant from = now.minus(Duration.ofDays(1));
            for (InstrumentRef instrument : catalog.all()) {
                try {
                    int filled = ingest.fillGaps(instrument, Timeframe.BASE, from, now);
                    if (filled > 0) {
                        log.info("{} için {} eksik mum dolduruldu", instrument.qualifiedSymbol(), filled);
                        rollupDerived(instrument, now);
                    }
                } catch (RuntimeException e) {
                    log.warn("{} boşluk doldurma başarısız: {}",
                            instrument.qualifiedSymbol(), e.getMessage());
                }
            }
        });
    }

    @Scheduled(fixedDelayString = "${investor.market-data.partition-interval:PT24H}")
    void maintainPartitions() {
        guard("partitions", partitions::ensureUpcoming);
    }

    /**
     * Üst zaman dilimlerini yeniden üretir.
     *
     * <p>Pencere kasten geniş: geç gelen bir taban mum, daha önce atlanmış bir kovayı
     * tamamlamış olabilir. Rollup idempotent olduğu için tekrar koşmanın maliyeti düşük.
     */
    private void rollupDerived(InstrumentRef instrument, Instant now) {
        for (Timeframe target : Timeframe.derived()) {
            Instant from = target.floor(now.minus(target.duration().multipliedBy(3)));
            Instant to = target.floor(now);
            if (to.isAfter(from)) {
                ingest.rollup(instrument, target, from, to);
            }
        }
    }

    private void guard(String job, Runnable work) {
        AtomicBoolean flag = running.computeIfAbsent(job, key -> new AtomicBoolean());
        if (!flag.compareAndSet(false, true)) {
            log.debug("{} işi hâlâ çalışıyor, bu tur atlandı", job);
            return;
        }
        try {
            work.run();
        } catch (RuntimeException e) {
            log.error("{} işi hata verdi", job, e);
        } finally {
            flag.set(false);
        }
    }
}
