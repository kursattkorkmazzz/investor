package com.investor.analysis;

import com.investor.analysis.internal.AnalysisInternals;
import com.investor.marketdata.MarketDataReader;

/**
 * Deterministik analiz bileşenlerinin kurucu noktası.
 *
 * <p>Spring bağlamı olmadan da kullanılabilmeleri gerekiyor: geri test koşucusu ve
 * ölçüm testleri bu bileşenleri düz Java nesnesi olarak kurup binlerce kez çağırıyor.
 * Yalnızca bean tanımı üzerinden erişilebilseydi, geri test bir uygulama bağlamı ayağa
 * kaldırmak zorunda kalırdı — hem yavaş hem de gereksiz bir bağımlılık.
 *
 * <p>Burada yalnızca <b>deterministik</b> bileşenler var. LLM tabanlı ajanlar bilinçli
 * olarak dışarıda: onlar bütçe, çağrı kaydı ve yapılandırma gerektiriyor ve bir fabrika
 * metodundan sessizce kurulmaları, maliyet kontrolünü atlatmanın kolay bir yolu olurdu.
 */
public final class AnalysisFactory {

    private AnalysisFactory() {
    }

    public static IndicatorService indicatorService(MarketDataReader reader) {
        return AnalysisInternals.indicatorService(reader);
    }

    public static StatsService statsService(MarketDataReader reader) {
        return AnalysisInternals.statsService(reader);
    }

    public static RegimeClassifier regimeClassifier() {
        return AnalysisInternals.regimeClassifier();
    }

    public static TriggerGate triggerGate() {
        return AnalysisInternals.triggerGate();
    }
}
