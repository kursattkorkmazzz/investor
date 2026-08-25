package com.investor.knowledge.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.investor.knowledge.MacroIngest;
import com.investor.knowledge.MacroSource;
import com.investor.knowledge.model.MacroPoint;
import com.investor.knowledge.model.MacroSeriesSpec;
import com.investor.ontology.model.ObjectRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makro seri toplama.
 *
 * <p>Her yayın sürümü ayrı bir geçerlilik aralığı olarak yazılır: bir revizyon eskisini
 * ezmez. Böylece "15 Ağustos'ta hangi CPI rakamını görüyorduk" sorusu, rakam Eylül'de
 * düzeltildikten sonra da doğru cevaplanır — backtest'in dürüstlüğü buna bağlı.
 */
class DefaultMacroIngest implements MacroIngest {

    private static final Logger log = LoggerFactory.getLogger(DefaultMacroIngest.class);

    private final MacroSource source;
    private final KnowledgeOntology ontology;
    private final IngestCursors cursors;
    private final Clock clock;
    private final List<String> configuredSeries;

    DefaultMacroIngest(MacroSource source, KnowledgeOntology ontology, IngestCursors cursors,
                       Clock clock, List<String> configuredSeries) {
        this.source = source;
        this.ontology = ontology;
        this.cursors = cursors;
        this.clock = clock;
        this.configuredSeries = List.copyOf(configuredSeries);
    }

    @Override
    public int syncAll() {
        int written = 0;
        for (String series : configuredSeries) {
            try {
                written += syncSeries(series, null);
            } catch (RuntimeException e) {
                log.warn("{} serisi güncellenemedi: {}", series, e.getMessage());
            }
        }
        return written;
    }

    @Override
    public int syncSeries(String seriesCode, LocalDate since) {
        ontology.ensureTypes();
        String cursorKey = "FRED:" + seriesCode;

        try {
            MacroSeriesSpec spec = source.describe(seriesCode)
                    .orElseGet(() -> MacroSeriesSpec.of(seriesCode, seriesCode));
            ObjectRef indicator = ontology.writeIndicator(spec, source.sourceName(), clock.instant());

            List<MacroPoint> points = source.observations(seriesCode, since);
            int written = 0;
            LocalDate latestPeriod = null;

            for (MacroPoint point : points) {
                if (ontology.writeObservation(indicator, point, null)) {
                    written++;
                }
                if (latestPeriod == null || point.period().isAfter(latestPeriod)) {
                    latestPeriod = point.period();
                }
            }

            cursors.recordSuccess(cursorKey,
                    latestPeriod == null ? null : latestPeriod.toString(), clock.instant());
            if (written > 0) {
                log.info("{}: {} yeni gözlem sürümü yazıldı", seriesCode, written);
            }
            return written;
        } catch (RuntimeException e) {
            cursors.recordFailure(cursorKey, clock.instant(), e.getMessage());
            throw e;
        }
    }
}
