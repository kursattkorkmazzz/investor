package com.investor.analysis.internal;

import com.investor.analysis.IndicatorService;
import com.investor.analysis.RegimeClassifier;
import com.investor.analysis.StatsService;
import com.investor.analysis.TriggerGate;
import com.investor.marketdata.MarketDataReader;

/**
 * İç gerçeklemeleri modülün genel API'sine açan tek kapı.
 *
 * <p>Sınıfların kendisi paket-özel kalıyor; dışarıya yalnızca arayüzler çıkıyor. Böylece
 * gerçekleme değiştirilebilir kalıyor ve kimse {@code new Ta4jIndicatorService(...)}
 * yazamıyor.
 */
public final class AnalysisInternals {

    private AnalysisInternals() {
    }

    public static IndicatorService indicatorService(MarketDataReader reader) {
        return new Ta4jIndicatorService(reader);
    }

    public static StatsService statsService(MarketDataReader reader) {
        return new DefaultStatsService(reader);
    }

    public static RegimeClassifier regimeClassifier() {
        return new RuleRegimeClassifier();
    }

    public static TriggerGate triggerGate() {
        return new DefaultTriggerGate();
    }
}
