package com.investor.knowledge.internal;

import java.util.concurrent.atomic.AtomicBoolean;

import com.investor.knowledge.MacroIngest;
import com.investor.knowledge.NewsIngest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Zamanlanmış bilgi toplama.
 *
 * <p>Piyasa verisindeki gibi, her iş kendi önceki koşusu bitmeden yeniden başlamaz.
 * Haber toplama yavaş olabiliyor (n besleme × ağ gecikmesi) ve üst üste binen koşular
 * hem kaynakları hem tekilleştirme mantığını zorlar.
 */
class KnowledgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeScheduler.class);

    private final NewsIngest newsIngest;
    private final MacroIngest macroIngest;
    private final KnowledgeProperties properties;
    private final AtomicBoolean newsRunning = new AtomicBoolean();
    private final AtomicBoolean macroRunning = new AtomicBoolean();

    KnowledgeScheduler(NewsIngest newsIngest, MacroIngest macroIngest, KnowledgeProperties properties) {
        this.newsIngest = newsIngest;
        this.macroIngest = macroIngest;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${investor.knowledge.news-interval:PT5M}")
    void collectNews() {
        if (!newsRunning.compareAndSet(false, true)) {
            log.debug("Haber toplama hâlâ çalışıyor, bu tur atlandı");
            return;
        }
        try {
            NewsIngest.IngestReport report = newsIngest.ingestAll();
            if (report.newClusters() > 0 || report.failedFeeds() > 0) {
                log.info("Haber turu: {} çekildi, {} yeni konu, {} mevcut konuya eklendi, "
                                + "{} kopya atlandı, {} besleme hata verdi",
                        report.fetched(), report.newClusters(), report.joinedExisting(),
                        report.duplicatesSkipped(), report.failedFeeds());
            }
        } catch (RuntimeException e) {
            log.error("Haber toplama turu hata verdi", e);
        } finally {
            newsRunning.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${investor.knowledge.macro-interval:PT1H}")
    void collectMacro() {
        if (!properties.macroEnabled()) {
            return;
        }
        if (!macroRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            int written = macroIngest.syncAll();
            if (written > 0) {
                log.info("Makro turu: {} yeni gözlem sürümü", written);
            }
        } catch (RuntimeException e) {
            log.error("Makro toplama turu hata verdi", e);
        } finally {
            macroRunning.set(false);
        }
    }
}
