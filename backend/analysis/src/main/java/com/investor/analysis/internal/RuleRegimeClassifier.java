package com.investor.analysis.internal;

import com.investor.analysis.RegimeClassifier;
import com.investor.analysis.model.IndicatorSet;
import com.investor.analysis.model.PriceStats;
import com.investor.analysis.model.Regime;

/**
 * Kural tabanlı rejim sınıflandırıcı.
 *
 * <h2>Trend</h2>
 * EMA hizalanmasına bakıyor: {@code ema20 > ema50 > ema200} yükseliş, tersi düşüş.
 * Klasik ve sıkıcı bir kural — ve bu bir erdem: rejim, kararın tamamının ağırlığını
 * belirlediği için burada yaratıcılık istemiyoruz.
 *
 * <h2>Ölü bant neden var</h2>
 * Bu sınıfın en önemli parametresi {@link #DEAD_BAND_PCT}. EMA'lar birbirine çok
 * yakınken hizalanma her mumda değişebilir; ölü bant olmadan sınıflandırma gürültüde
 * savrulur. Bunun iki maliyeti var ve ikisi de gerçek:
 * <ul>
 *   <li><b>Para:</b> rejim değişimi tetikleyici kapısını açıyor. Savrulan bir
 *       sınıflandırıcı her turda pahalı bir LLM turu başlatır.</li>
 *   <li><b>Karar kalitesi:</b> "rejim değişti" sinyali bir şey ifade etmeyi bırakır.</li>
 * </ul>
 * Ölü bandın içindeyken cevap {@link Regime.Trend#RANGE} — ki bu zaten doğru cevap:
 * ayrışmamış EMA'lar tam olarak yatay seyri anlatır.
 *
 * <h2>Oynaklık</h2>
 * Mutlak eşik yerine <em>kendi tarihsel dağılımındaki persentil</em>. %3 yıllık oynaklık
 * bir hisse için yüksek, bir altcoin için uykudur; sabit eşik varlıklar arasında
 * taşınamaz.
 */
class RuleRegimeClassifier implements RegimeClassifier {

    /**
     * Hizalanmanın sayılması için gereken en az göreli ayrışma, %.
     *
     * <p>%0.15: 1 saatlik mumlarda tipik gürültünün üstünde, gerçek bir trendin
     * başlangıcında hızla aşılan bir eşik. Gerçek veriyle yeniden kalibre edilmeli —
     * şu anki değer sentetik seriler üzerinde seçildi ve bu bir borç
     * ({@code docs/05-analiz-ajanlari.md}).
     */
    static final double DEAD_BAND_PCT = 0.15;

    /** Persentil eşiği: bunun altı sakin. */
    static final double LOW_VOL_PERCENTILE = 25;

    /** Persentil eşiği: bunun üstü oynak. */
    static final double HIGH_VOL_PERCENTILE = 75;

    @Override
    public Regime classify(IndicatorSet indicators, PriceStats stats) {
        if (indicators == null || stats == null) {
            return Regime.unknown("gösterge ya da istatistik kümesi yok");
        }
        if (!indicators.has("ema20") || !indicators.has("ema50") || !indicators.has("ema200")) {
            // Isınmamış EMA'yı sıfır sayıp trend uydurmuyoruz.
            return Regime.unknown("EMA hizalanması için yeterli mum yok (ema20/50/200 eksik)");
        }

        double ema20 = indicators.get("ema20").orElseThrow().doubleValue();
        double ema50 = indicators.get("ema50").orElseThrow().doubleValue();
        double ema200 = indicators.get("ema200").orElseThrow().doubleValue();
        if (ema200 <= 0) {
            return Regime.unknown("EMA200 pozitif değil; oran hesaplanamıyor");
        }

        // Ayrışma, en uzun EMA'ya oranlanıyor: fiyat ölçeğinden bağımsız kalsın.
        double shortMid = (ema20 - ema50) / ema200 * 100;
        double midLong = (ema50 - ema200) / ema200 * 100;
        double strength = Math.min(Math.abs(shortMid), Math.abs(midLong));

        Regime.Trend trend;
        String trendReason;
        if (shortMid > DEAD_BAND_PCT && midLong > DEAD_BAND_PCT) {
            trend = Regime.Trend.UPTREND;
            trendReason = "EMA20 > EMA50 > EMA200, ayrışma %%%.2f".formatted(strength);
        } else if (shortMid < -DEAD_BAND_PCT && midLong < -DEAD_BAND_PCT) {
            trend = Regime.Trend.DOWNTREND;
            trendReason = "EMA20 < EMA50 < EMA200, ayrışma %%%.2f".formatted(strength);
        } else {
            trend = Regime.Trend.RANGE;
            trendReason = "EMA'lar ayrışmamış (ayrışma %%%.2f, ölü bant %%%.2f); yatay seyir"
                    .formatted(strength, DEAD_BAND_PCT);
        }

        Regime.Volatility volatility;
        String volReason;
        if (stats.has("volPercentile")) {
            double pct = stats.get("volPercentile").orElseThrow().doubleValue();
            if (pct < LOW_VOL_PERCENTILE) {
                volatility = Regime.Volatility.LOW;
            } else if (pct > HIGH_VOL_PERCENTILE) {
                volatility = Regime.Volatility.HIGH;
            } else {
                volatility = Regime.Volatility.NORMAL;
            }
            volReason = "oynaklık kendi geçmişinin %.0f. persentilinde".formatted(pct);
        } else {
            volatility = Regime.Volatility.UNKNOWN;
            volReason = "oynaklık persentili için yeterli gözlem yok";
        }

        return new Regime(trend, volatility, round(strength), trendReason + "; " + volReason);
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
