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
 * <h2>Histerezis: bu sınıfın en önemli özelliği</h2>
 * İlk sürüm yalnızca simetrik bir ölü bant kullanıyordu ve ölçüm bunun yetmediğini
 * gösterdi: rastgele yürüyüş üzerinde 1500 mumda 248 rejim değişimi, yani her 6 mumda
 * bir. Rejim değişimi pahalı bir LLM turu açtığı için bu, doğrudan para demek.
 *
 * <p>İki kaynak vardı. Birincisi eşiğin <em>simetrik</em> olması. Bir ölçüm eşiğin etrafında
 * dolaşırken (oynaklık persentili 74–76 arası, EMA ayrışması ölü bandın kenarında)
 * sınıflandırma her mumda taraf değiştirir. Çözüm asimetri: <b>bir rejime girmek, o
 * rejimde kalmaktan zordur.</b>
 *
 * <ul>
 *   <li>Trend: girmek için ayrışma {@code > %0.15}, kalmak için {@code > %0.08}</li>
 *   <li>Yüksek oynaklık: girmek için persentil {@code > 75}, kalmak için {@code > 65}</li>
 *   <li>Düşük oynaklık: girmek için {@code < 25}, kalmak için {@code < 35}</li>
 * </ul>
 *
 * <p>İkincisi ve daha derini kavramsaldı: rejim, kısa vadeli oynaklık ölçüsüne bağlıydı.
 * <b>Rejim yavaş değişen bir karakterdir, tetikleyici ise hızlı bir olaydır.</b> Kısa
 * vadeli bir oynaklık sıçramasını "piyasanın karakteri değişti" diye okumak yanlıştı;
 * sıçramalar zaten PRICE_SHOCK ve VOLUME_ANOMALY ile yakalanıyor. Rejim artık
 * {@link #VOLATILITY_INPUT} — yaklaşık dört günlük pencere — kullanıyor.
 *
 * <p>Bunun bedeli, rejim değişiminin birkaç mum gecikmeli görünmesi. Kabul edilebilir bir
 * takas: gerçek bir rejim değişimi zaten birkaç mum sürer, sahte olan ise geçer.
 *
 * <h2>Oynaklık neden persentil</h2>
 * Mutlak eşik yerine kendi tarihsel dağılımındaki konum. %3 yıllık oynaklık bir hisse
 * için yüksek, bir altcoin için uykudur; sabit eşik varlıklar arasında taşınamaz.
 */
class RuleRegimeClassifier implements RegimeClassifier {

    /** Trende <em>girmek</em> için gereken en az göreli EMA ayrışması, %. */
    static final double TREND_ENTER_PCT = 0.15;

    /** Trendde <em>kalmak</em> için yeterli ayrışma, %. Girişten düşük — histerezis. */
    static final double TREND_EXIT_PCT = 0.08;

    static final double HIGH_VOL_ENTER = 80;
    static final double HIGH_VOL_EXIT = 60;
    static final double LOW_VOL_ENTER = 20;
    static final double LOW_VOL_EXIT = 40;

    /**
     * Rejim, <em>yavaş</em> oynaklık ölçüsünü kullanıyor.
     *
     * <p>Kısa vadeli ölçü kullanıldığında 1500 mumda 103 oynaklık rejimi değişimi
     * çıkıyordu; yavaş ölçüyle 35'e iniyor. Ayrıntı:
     * {@link DefaultStatsService#SLOW_WINDOW}.
     */
    static final String VOLATILITY_INPUT = "volPercentileSlow";

    @Override
    public Regime classify(IndicatorSet indicators, PriceStats stats, Regime previous) {
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

        // Ayrışma en uzun EMA'ya oranlanıyor: fiyat ölçeğinden bağımsız kalsın.
        double shortMid = (ema20 - ema50) / ema200 * 100;
        double midLong = (ema50 - ema200) / ema200 * 100;
        double strength = Math.min(Math.abs(shortMid), Math.abs(midLong));

        Regime.Trend previousTrend = previous == null ? null : previous.trend();
        double threshold = trendThreshold(previousTrend, shortMid, midLong);

        Regime.Trend trend;
        String trendReason;
        if (shortMid > threshold && midLong > threshold) {
            trend = Regime.Trend.UPTREND;
            trendReason = "EMA20 > EMA50 > EMA200, ayrışma %%%.2f (eşik %%%.2f)"
                    .formatted(strength, threshold);
        } else if (shortMid < -threshold && midLong < -threshold) {
            trend = Regime.Trend.DOWNTREND;
            trendReason = "EMA20 < EMA50 < EMA200, ayrışma %%%.2f (eşik %%%.2f)"
                    .formatted(strength, threshold);
        } else {
            trend = Regime.Trend.RANGE;
            trendReason = "EMA'lar ayrışmamış (ayrışma %%%.2f, eşik %%%.2f); yatay seyir"
                    .formatted(strength, threshold);
        }

        Regime.Volatility volatility;
        String volReason;
        if (stats.has(VOLATILITY_INPUT)) {
            double pct = stats.get(VOLATILITY_INPUT).orElseThrow().doubleValue();
            volatility = volatility(pct, previous == null ? null : previous.volatility());
            volReason = "oynaklık (yavaş ölçü) kendi geçmişinin %.0f. persentilinde".formatted(pct);
        } else {
            // Kısa vadeli ölçüye düşmüyoruz: düşseydik histerezisin çözdüğü savrulma
            // sessizce geri gelirdi.
            volatility = Regime.Volatility.UNKNOWN;
            volReason = "yavaş oynaklık persentili için yeterli gözlem yok";
        }

        return new Regime(trend, volatility, round(strength), trendReason + "; " + volReason);
    }

    /**
     * Uygulanacak eşik: aynı yönde bir trendden geliyorsak gevşek, değilse sıkı.
     *
     * <p>Yön kontrolü önemli: yükselişten düşüşe geçerken gevşek eşik kullanmak,
     * histerezisin amacını tersine çevirirdi.
     */
    private static double trendThreshold(Regime.Trend previous, double shortMid, double midLong) {
        boolean continuingUp = previous == Regime.Trend.UPTREND && shortMid > 0 && midLong > 0;
        boolean continuingDown = previous == Regime.Trend.DOWNTREND && shortMid < 0 && midLong < 0;
        return continuingUp || continuingDown ? TREND_EXIT_PCT : TREND_ENTER_PCT;
    }

    private static Regime.Volatility volatility(double pct, Regime.Volatility previous) {
        if (previous == Regime.Volatility.HIGH) {
            return pct >= HIGH_VOL_EXIT ? Regime.Volatility.HIGH : normalOrLow(pct);
        }
        if (previous == Regime.Volatility.LOW) {
            return pct <= LOW_VOL_EXIT ? Regime.Volatility.LOW : normalOrHigh(pct);
        }
        if (pct > HIGH_VOL_ENTER) {
            return Regime.Volatility.HIGH;
        }
        if (pct < LOW_VOL_ENTER) {
            return Regime.Volatility.LOW;
        }
        return Regime.Volatility.NORMAL;
    }

    private static Regime.Volatility normalOrLow(double pct) {
        return pct < LOW_VOL_ENTER ? Regime.Volatility.LOW : Regime.Volatility.NORMAL;
    }

    private static Regime.Volatility normalOrHigh(double pct) {
        return pct > HIGH_VOL_ENTER ? Regime.Volatility.HIGH : Regime.Volatility.NORMAL;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
