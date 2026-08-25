package com.investor.analysis.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.investor.analysis.model.IndicatorSet;
import com.investor.analysis.model.IndicatorValue;
import com.investor.analysis.model.PriceStats;
import com.investor.analysis.model.Regime;
import com.investor.analysis.model.StatValue;
import com.investor.analysis.support.TestBars;
import com.investor.marketdata.model.Timeframe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rejim sınıflandırıcı testleri.
 *
 * <p>Çoğu test göstergeleri elle kuruyor: gerçek bir seriden istenen EMA hizalanmasını
 * üretmek dolaylı ve kırılgan olurdu. İki test ise uçtan uca — gerçek serilerden geçen
 * boru hattının beklenen rejimi verdiğini doğruluyor.
 */
class RuleRegimeClassifierTest {

    private final RuleRegimeClassifier classifier = new RuleRegimeClassifier();

    @Test
    @DisplayName("Hizalanmış ve ayrışmış EMA'lar yükseliş trendi")
    void alignedEmasGiveUptrend() {
        Regime regime = classifier.classify(
                indicators(Map.of("ema20", 105.0, "ema50", 102.0, "ema200", 100.0)),
                stats(Map.of("volPercentileSlow", 50.0)), null);

        assertThat(regime.trend()).isEqualTo(Regime.Trend.UPTREND);
        assertThat(regime.volatility()).isEqualTo(Regime.Volatility.NORMAL);
        assertThat(regime.label()).isEqualTo("UPTREND/NORMAL");
        assertThat(regime.rationale()).contains("EMA20 > EMA50 > EMA200");
    }

    @Test
    @DisplayName("Ters hizalanma düşüş trendi")
    void reverseAlignmentGivesDowntrend() {
        Regime regime = classifier.classify(
                indicators(Map.of("ema20", 95.0, "ema50", 98.0, "ema200", 100.0)),
                stats(Map.of("volPercentileSlow", 90.0)), null);

        assertThat(regime.trend()).isEqualTo(Regime.Trend.DOWNTREND);
        assertThat(regime.volatility()).isEqualTo(Regime.Volatility.HIGH);
    }

    @Test
    @DisplayName("Ölü bandın içindeki ayrışma yatay sayılıyor — gürültüde savrulmuyor")
    void deadBandSuppressesNoise() {
        // Hizalanma "doğru" ama ayrışma %0.05 — ölü bandın (%0.15) altında.
        Regime regime = classifier.classify(
                indicators(Map.of("ema20", 100.10, "ema50", 100.05, "ema200", 100.0)),
                stats(Map.of("volPercentileSlow", 50.0)), null);

        // Ölü bant olmasaydı bu UPTREND olurdu ve bir sonraki mumda DOWNTREND olabilirdi;
        // her savrulma bir LLM turu demek.
        assertThat(regime.trend()).isEqualTo(Regime.Trend.RANGE);
        assertThat(regime.rationale()).contains("ayrışmamış");
    }

    @Test
    @DisplayName("Ölü bandın hemen üstündeki ayrışma trend sayılıyor")
    void justAboveDeadBandIsTrend() {
        // ema50-ema200 ayrışması %0.2, ema20-ema50 ayrışması %0.2 — ikisi de eşiğin üstünde.
        Regime regime = classifier.classify(
                indicators(Map.of("ema20", 100.4, "ema50", 100.2, "ema200", 100.0)),
                stats(Map.of("volPercentileSlow", 50.0)), null);

        assertThat(regime.trend()).isEqualTo(Regime.Trend.UPTREND);
    }

    @Test
    @DisplayName("Karışık hizalanma yatay")
    void mixedAlignmentIsRange() {
        // ema20 yüksek ama ema50 ema200'ün altında — tutarlı bir trend yok.
        Regime regime = classifier.classify(
                indicators(Map.of("ema20", 105.0, "ema50", 98.0, "ema200", 100.0)),
                stats(Map.of("volPercentileSlow", 50.0)), null);

        assertThat(regime.trend()).isEqualTo(Regime.Trend.RANGE);
    }

    @Test
    @DisplayName("EMA eksikse trend UNKNOWN — yatay diye iddiada bulunmuyor")
    void missingEmaGivesUnknown() {
        Regime regime = classifier.classify(
                indicators(Map.of("ema20", 105.0, "ema50", 102.0)),
                stats(Map.of("volPercentileSlow", 50.0)), null);

        // RANGE dönseydi "yatay seyrediyor" diye bir iddiada bulunmuş olurduk; oysa
        // elimizde iddia edecek veri yok.
        assertThat(regime.trend()).isEqualTo(Regime.Trend.UNKNOWN);
        assertThat(regime.isKnown()).isFalse();
        assertThat(regime.rationale()).contains("yeterli mum yok");
    }

    @Test
    @DisplayName("Oynaklık persentili yoksa yalnızca oynaklık UNKNOWN, trend hesaplanıyor")
    void missingVolatilityDoesNotBlockTrend() {
        Regime regime = classifier.classify(
                indicators(Map.of("ema20", 105.0, "ema50", 102.0, "ema200", 100.0)),
                stats(Map.of()), null);

        assertThat(regime.trend()).isEqualTo(Regime.Trend.UPTREND);
        assertThat(regime.volatility()).isEqualTo(Regime.Volatility.UNKNOWN);
        assertThat(regime.isKnown()).isFalse();
    }

    @Test
    @DisplayName("Oynaklık eşikleri: önceki durum yokken giriş eşikleri geçerli")
    void volatilityEntryThresholds() {
        assertThat(volatilityAt(10, null)).isEqualTo(Regime.Volatility.LOW);
        assertThat(volatilityAt(25, null)).isEqualTo(Regime.Volatility.NORMAL);
        assertThat(volatilityAt(50, null)).isEqualTo(Regime.Volatility.NORMAL);
        assertThat(volatilityAt(75, null)).isEqualTo(Regime.Volatility.NORMAL);
        assertThat(volatilityAt(90, null)).isEqualTo(Regime.Volatility.HIGH);
    }

    @Test
    @DisplayName("Histerezis: bir rejime girmek, o rejimde kalmaktan zor")
    void volatilityHysteresis() {
        Regime high = new Regime(Regime.Trend.RANGE, Regime.Volatility.HIGH, 0, "");
        Regime low = new Regime(Regime.Trend.RANGE, Regime.Volatility.LOW, 0, "");

        // 70. persentil: HIGH'a girmeye yetmez (80 gerekir) ama HIGH'da kalmaya yeter (60).
        assertThat(volatilityAt(70, null)).isEqualTo(Regime.Volatility.NORMAL);
        assertThat(volatilityAt(70, high)).isEqualTo(Regime.Volatility.HIGH);

        // 30. persentil: LOW'a girmeye yetmez (20 gerekir) ama LOW'da kalmaya yeter (40).
        assertThat(volatilityAt(30, null)).isEqualTo(Regime.Volatility.NORMAL);
        assertThat(volatilityAt(30, low)).isEqualTo(Regime.Volatility.LOW);

        // Bandın dışına çıkınca gerçekten terk ediliyor — histerezis yapışkanlık değil.
        assertThat(volatilityAt(55, high)).isEqualTo(Regime.Volatility.NORMAL);
        assertThat(volatilityAt(45, low)).isEqualTo(Regime.Volatility.NORMAL);
    }

    @Test
    @DisplayName("Trend histerezisi yalnızca aynı yönde geçerli")
    void trendHysteresisIsDirectional() {
        Regime up = new Regime(Regime.Trend.UPTREND, Regime.Volatility.NORMAL, 1, "");
        Regime down = new Regime(Regime.Trend.DOWNTREND, Regime.Volatility.NORMAL, 1, "");

        // Ayrışma %0.10: girmeye yetmez (%0.15), kalmaya yeter (%0.08).
        var weak = indicators(Map.of("ema20", 100.20, "ema50", 100.10, "ema200", 100.0));
        var st = stats(Map.of("volPercentileSlow", 50.0));

        assertThat(classifier.classify(weak, st, null).trend()).isEqualTo(Regime.Trend.RANGE);
        assertThat(classifier.classify(weak, st, up).trend()).isEqualTo(Regime.Trend.UPTREND);
        // Düşüşten geliyorsak gevşek eşik uygulanmıyor: yön kontrolü olmasaydı histerezis
        // amacının tersine çalışırdı.
        assertThat(classifier.classify(weak, st, down).trend()).isEqualTo(Regime.Trend.RANGE);
    }

    @Test
    @DisplayName("differsFrom yalnızca kategoriye bakıyor, güce değil")
    void differsFromIgnoresStrength() {
        Regime weak = new Regime(Regime.Trend.UPTREND, Regime.Volatility.NORMAL, 0.2, "");
        Regime strong = new Regime(Regime.Trend.UPTREND, Regime.Volatility.NORMAL, 8.0, "");

        // Sürekli bir sayının her kıpırdanışı "rejim değişti" sayılsaydı tetikleyici
        // kapısı her turda açılır ve maliyet tasarımı çökerdi.
        assertThat(weak.differsFrom(strong)).isFalse();
        assertThat(weak.differsFrom(null)).isTrue();
        assertThat(weak.differsFrom(
                new Regime(Regime.Trend.UPTREND, Regime.Volatility.HIGH, 0.2, ""))).isTrue();
    }

    @Test
    @DisplayName("Uçtan uca: yükselen gerçek seri yükseliş trendi veriyor")
    void endToEndUptrend() {
        Regime regime = classifyPipeline(TestBars.ramp(900, 100, 0.2));

        assertThat(regime.trend()).isEqualTo(Regime.Trend.UPTREND);
    }

    @Test
    @DisplayName("Uçtan uca: düşen gerçek seri düşüş trendi veriyor")
    void endToEndDowntrend() {
        Regime regime = classifyPipeline(TestBars.ramp(900, 400, -0.2));

        assertThat(regime.trend()).isEqualTo(Regime.Trend.DOWNTREND);
    }

    // ---- yardımcılar ----

    /** Gerçek gösterge ve istatistik servislerinden geçen tam boru hattı. */
    private Regime classifyPipeline(List<com.investor.marketdata.model.Bar> bars) {
        TestBars.StubReader reader = new TestBars.StubReader(bars);
        Instant asOf = TestBars.T0.plus(Duration.ofHours(bars.size() + 10));
        return classifier.classify(
                new Ta4jIndicatorService(reader).compute(TestBars.BTC, Timeframe.H1, asOf),
                new DefaultStatsService(reader).compute(TestBars.BTC, Timeframe.H1, asOf), null);
    }

    private Regime.Volatility volatilityAt(double percentile, Regime previous) {
        return classifier.classify(
                indicators(Map.of("ema20", 105.0, "ema50", 102.0, "ema200", 100.0)),
                stats(Map.of("volPercentileSlow", percentile)), previous).volatility();
    }

    private static IndicatorSet indicators(Map<String, Double> raw) {
        Map<String, IndicatorValue> values = new LinkedHashMap<>();
        raw.forEach((k, v) -> values.put(k,
                new IndicatorValue(k, BigDecimal.valueOf(v), 1, 500, "test")));
        return new IndicatorSet(TestBars.BTC, Timeframe.H1, TestBars.T0, TestBars.T0, 500,
                values, List.of());
    }

    private static PriceStats stats(Map<String, Double> raw) {
        Map<String, StatValue> values = new LinkedHashMap<>();
        raw.forEach((k, v) -> values.put(k, new StatValue(k, BigDecimal.valueOf(v), 500, "test")));
        return new PriceStats(TestBars.BTC, Timeframe.H1, TestBars.T0, 500, values);
    }
}
