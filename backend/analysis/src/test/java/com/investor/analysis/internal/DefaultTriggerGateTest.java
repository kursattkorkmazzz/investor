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
import com.investor.analysis.model.Trigger;
import com.investor.analysis.model.TriggerContext;
import com.investor.analysis.model.TriggerDecision;
import com.investor.analysis.support.TestBars;
import com.investor.marketdata.model.Timeframe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tetikleyici kapısı testleri.
 *
 * <p>Kapının işi tur <em>açmak</em> değil, çoğu turu <em>açmamak</em>. Testlerin
 * yarısından fazlası bu yüzden "tetiklemediğini" doğruluyor: yanlış açılan bir tur
 * doğrudan para kaybı ve kapının tamamı bu tasarrufun üstünde duruyor.
 */
class DefaultTriggerGateTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    private final DefaultTriggerGate gate = new DefaultTriggerGate();

    @Test
    @DisplayName("Hiçbir şey olmadığında kapı kapalı")
    void closedWhenNothingHappens() {
        TriggerDecision decision = gate.evaluate(context()
                .indicators(indicators(Map.of("rsi14", 50.0, "macdHistogram", 0.5,
                                "bbPercentB", 0.5, "atrPercent", 1.0, "close", 100.0)),
                        indicators(Map.of("rsi14", 51.0, "macdHistogram", 0.4,
                                "bbPercentB", 0.55, "atrPercent", 1.0)))
                .stats(stats(Map.of("volumeZScore", 0.3, "return1", 0.4)))
                .build());

        assertThat(decision.open()).isFalse();
        assertThat(decision.summary()).isEqualTo("kapalı");
    }

    @Test
    @DisplayName("RSI aşırı satım bölgesine GİRİŞ tetikliyor")
    void rsiEntryTriggers() {
        TriggerDecision decision = gate.evaluate(context()
                .indicators(indicators(Map.of("rsi14", 28.0)), indicators(Map.of("rsi14", 33.0)))
                .build());

        assertThat(decision.open()).isTrue();
        assertThat(decision.hasType(Trigger.Type.RSI_EXTREME)).isTrue();
        assertThat(decision.reasons().get(0).detail()).contains("aşırı satım");
    }

    @Test
    @DisplayName("RSI bölgede KALMAK tetiklemiyor — kapıyı işlevsiz kılardı")
    void rsiStayingInZoneDoesNotTrigger() {
        // Bu testin koruduğu şey: düşen bir piyasada RSI günlerce 30'un altında kalabilir.
        // Durum tetikleyici sayılsaydı her tur açılır ve kapı hiçbir işe yaramazdı.
        TriggerDecision decision = gate.evaluate(context()
                .indicators(indicators(Map.of("rsi14", 25.0)), indicators(Map.of("rsi14", 28.0)))
                .build());

        assertThat(decision.open()).isFalse();
    }

    @Test
    @DisplayName("RSI aşırı alım bölgesine giriş de tetikliyor")
    void rsiOverboughtEntryTriggers() {
        assertThat(gate.evaluate(context()
                .indicators(indicators(Map.of("rsi14", 72.0)), indicators(Map.of("rsi14", 68.0)))
                .build()).hasType(Trigger.Type.RSI_EXTREME)).isTrue();
    }

    @Test
    @DisplayName("Anlamlı MACD kesişimi tetikliyor")
    void macdCrossTriggers() {
        // Fiyat 100, histogram 0.2 → fiyatın %0.2'si, eşiğin (%0.05) üstünde.
        TriggerDecision up = gate.evaluate(context()
                .indicators(indicators(Map.of("macdHistogram", 0.2, "close", 100.0)),
                        indicators(Map.of("macdHistogram", -0.1)))
                .build());
        TriggerDecision down = gate.evaluate(context()
                .indicators(indicators(Map.of("macdHistogram", -0.2, "close", 100.0)),
                        indicators(Map.of("macdHistogram", 0.1)))
                .build());

        assertThat(up.hasType(Trigger.Type.MACD_CROSS)).isTrue();
        assertThat(down.hasType(Trigger.Type.MACD_CROSS)).isTrue();
    }

    @Test
    @DisplayName("Sıfıra yakın MACD kesişimi tetiklemiyor — gürültü")
    void insignificantMacdCrossDoesNotTrigger() {
        // Histogram fiyatın yalnızca %0.001'i. Ölçümde bu tür kesişimler 1500 mumda
        // 121 sahte tetikleme üretiyordu.
        assertThat(gate.evaluate(context()
                .indicators(indicators(Map.of("macdHistogram", 0.001, "close", 100.0)),
                        indicators(Map.of("macdHistogram", -0.001)))
                .build()).open()).isFalse();
    }

    @Test
    @DisplayName("Aynı işarette kalan MACD tetiklemiyor")
    void macdSameSignDoesNotTrigger() {
        assertThat(gate.evaluate(context()
                .indicators(indicators(Map.of("macdHistogram", 0.9, "close", 100.0)),
                        indicators(Map.of("macdHistogram", 0.2)))
                .build()).open()).isFalse();
    }

    @Test
    @DisplayName("Bollinger bandı dışına belirgin çıkış tetikliyor, dışarıda kalmak tetiklemiyor")
    void bollingerBreakoutTriggersOnCrossingOnly() {
        assertThat(gate.evaluate(context()
                .indicators(indicators(Map.of("bbPercentB", 1.20)),
                        indicators(Map.of("bbPercentB", 0.95)))
                .build()).hasType(Trigger.Type.BOLLINGER_BREAKOUT)).isTrue();

        assertThat(gate.evaluate(context()
                .indicators(indicators(Map.of("bbPercentB", 1.40)),
                        indicators(Map.of("bbPercentB", 1.20)))
                .build()).open()).isFalse();
    }

    @Test
    @DisplayName("Bandın ucundan değmek kırılım sayılmıyor")
    void marginalBollingerTouchIsNotBreakout() {
        // 2σ bandının dışına çıkmak tanımı gereği zamanın ~%5'inde olan bir şey;
        // her değişi kırılım saymak kapıyı gürültüyle doldurur.
        assertThat(gate.evaluate(context()
                .indicators(indicators(Map.of("bbPercentB", 1.02)),
                        indicators(Map.of("bbPercentB", 0.95)))
                .build()).open()).isFalse();
    }

    @Test
    @DisplayName("Hacim anomalisi geçiş aramıyor — tek mumluk olay")
    void volumeAnomalyTriggersOnState() {
        TriggerDecision decision = gate.evaluate(context()
                .stats(stats(Map.of("volumeZScore", 4.2)))
                .build());

        assertThat(decision.hasType(Trigger.Type.VOLUME_ANOMALY)).isTrue();
        assertThat(decision.reasons().get(0).magnitude()).isEqualTo(4.2);
    }

    @Test
    @DisplayName("Fiyat şoku eşiği ATR'ye göre ölçekleniyor")
    void priceShockThresholdScalesWithAtr() {
        // %3'lük hareket: ATR %1 olan sakin varlıkta şok (eşik %2), ATR %5 olan oynak
        // varlıkta sıradan (eşik %10). Mutlak eşik kullanılsaydı ikisi de aynı sayılırdı.
        TriggerDecision calm = gate.evaluate(context()
                .indicators(indicators(Map.of("atrPercent", 1.0)), indicators(Map.of()))
                .stats(stats(Map.of("return1", 3.0)))
                .build());
        TriggerDecision volatile_ = gate.evaluate(context()
                .indicators(indicators(Map.of("atrPercent", 5.0)), indicators(Map.of()))
                .stats(stats(Map.of("return1", 3.0)))
                .build());

        assertThat(calm.hasType(Trigger.Type.PRICE_SHOCK)).isTrue();
        assertThat(volatile_.open()).isFalse();
    }

    @Test
    @DisplayName("Fiyat şoku düşüşte de tetikliyor")
    void priceShockTriggersOnDrops() {
        assertThat(gate.evaluate(context()
                .indicators(indicators(Map.of("atrPercent", 1.0)), indicators(Map.of()))
                .stats(stats(Map.of("return1", -3.0)))
                .build()).hasType(Trigger.Type.PRICE_SHOCK)).isTrue();
    }

    @Test
    @DisplayName("Önemli haber tetikliyor, önemsiz haber tetiklemiyor")
    void materialNewsTriggers() {
        assertThat(gate.evaluate(context().news(0.75).build())
                .hasType(Trigger.Type.MATERIAL_NEWS)).isTrue();
        assertThat(gate.evaluate(context().news(0.55).build()).open()).isFalse();
    }

    @Test
    @DisplayName("Rejim değişimi tetikliyor")
    void regimeChangeTriggers() {
        TriggerDecision decision = gate.evaluate(context()
                .regime(regime(Regime.Trend.UPTREND, Regime.Volatility.NORMAL),
                        regime(Regime.Trend.RANGE, Regime.Volatility.NORMAL))
                .build());

        assertThat(decision.hasType(Trigger.Type.REGIME_CHANGE)).isTrue();
        assertThat(decision.reasons().get(0).detail()).contains("RANGE/NORMAL", "UPTREND/NORMAL");
    }

    @Test
    @DisplayName("Bilinmeyene düşmek tetiklemiyor — piyasa hakkında değil bizim hakkımızda")
    void fallingToUnknownDoesNotTrigger() {
        TriggerDecision decision = gate.evaluate(context()
                .regime(Regime.unknown("veri yok"),
                        regime(Regime.Trend.UPTREND, Regime.Volatility.NORMAL))
                .build());

        assertThat(decision.open()).isFalse();
    }

    @Test
    @DisplayName("Bilinmeyenden bilinene geçiş tetikliyor — o gerçekten yeni bilgi")
    void risingFromUnknownTriggers() {
        assertThat(gate.evaluate(context()
                .regime(regime(Regime.Trend.UPTREND, Regime.Volatility.NORMAL),
                        Regime.unknown("veri yoktu"))
                .build()).hasType(Trigger.Type.REGIME_CHANGE)).isTrue();
    }

    @Test
    @DisplayName("Aynı rejim tetiklemiyor")
    void sameRegimeDoesNotTrigger() {
        Regime same = regime(Regime.Trend.UPTREND, Regime.Volatility.NORMAL);
        assertThat(gate.evaluate(context().regime(same, same).build()).open()).isFalse();
    }

    @Test
    @DisplayName("Açık pozisyon 4 saatte bir gözden geçiriliyor")
    void scheduledReviewForOpenPosition() {
        assertThat(gate.evaluate(context().openPosition(true)
                .lastRoundAt(NOW.minus(Duration.ofHours(5))).build())
                .hasType(Trigger.Type.SCHEDULED_REVIEW)).isTrue();

        assertThat(gate.evaluate(context().openPosition(true)
                .lastRoundAt(NOW.minus(Duration.ofHours(2))).build()).open()).isFalse();
    }

    @Test
    @DisplayName("Pozisyon yokken planlı gözden geçirme yok")
    void noScheduledReviewWithoutPosition() {
        // Bakacak bir şey olmadığında bakmanın bedeli var.
        assertThat(gate.evaluate(context().openPosition(false)
                .lastRoundAt(NOW.minus(Duration.ofDays(30))).build()).open()).isFalse();
    }

    @Test
    @DisplayName("Eksik gösterge tetikleyici üretmiyor, patlamıyor")
    void missingIndicatorsAreSafe() {
        assertThat(gate.evaluate(context().build()).open()).isFalse();
        assertThat(gate.evaluate(context().indicators(indicators(Map.of("rsi14", 20.0)), null)
                .build()).open()).isFalse();
    }

    @Test
    @DisplayName("Birden çok sebep birlikte kaydediliyor")
    void multipleReasonsAreRecorded() {
        TriggerDecision decision = gate.evaluate(context()
                .indicators(indicators(Map.of("rsi14", 25.0, "atrPercent", 1.0)),
                        indicators(Map.of("rsi14", 35.0)))
                .stats(stats(Map.of("volumeZScore", 5.0, "return1", -4.0)))
                .news(0.9)
                .build());

        // Sebeplerin hepsi kayda giriyor: sonradan "hangi tetikleyici işe yaradı"
        // sorusu ancak böyle cevaplanabilir.
        assertThat(decision.reasons()).hasSize(4);
        assertThat(decision.summary())
                .contains("RSI_EXTREME", "VOLUME_ANOMALY", "PRICE_SHOCK", "MATERIAL_NEWS");
    }

    @Test
    @DisplayName("Sebepsiz açık karar üretilemiyor")
    void cannotOpenWithoutReasons() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TriggerDecision.opened(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- yardımcılar ----

    private static TriggerContext.Builder context() {
        return TriggerContext.at(TestBars.BTC, NOW);
    }

    private static IndicatorSet indicators(Map<String, Double> raw) {
        Map<String, IndicatorValue> values = new LinkedHashMap<>();
        raw.forEach((k, v) -> values.put(k,
                new IndicatorValue(k, BigDecimal.valueOf(v), 14, 500, "test")));
        return new IndicatorSet(TestBars.BTC, Timeframe.H1, NOW, NOW, 500, values, List.of());
    }

    private static PriceStats stats(Map<String, Double> raw) {
        Map<String, StatValue> values = new LinkedHashMap<>();
        raw.forEach((k, v) -> values.put(k, new StatValue(k, BigDecimal.valueOf(v), 500, "test")));
        return new PriceStats(TestBars.BTC, Timeframe.H1, NOW, 500, values);
    }

    private static Regime regime(Regime.Trend trend, Regime.Volatility volatility) {
        return new Regime(trend, volatility, 1.0, "test");
    }
}
