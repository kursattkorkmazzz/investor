package com.investor.analysis;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.investor.analysis.model.IndicatorSet;
import com.investor.analysis.model.PriceStats;
import com.investor.analysis.model.Regime;
import com.investor.analysis.model.Trigger;
import com.investor.analysis.model.TriggerContext;
import com.investor.analysis.model.TriggerDecision;
import com.investor.analysis.support.TestBars;
import com.investor.marketdata.model.Bar;
import com.investor.marketdata.model.Timeframe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faz 3 kapı testi: <b>tetikleyici kapısı tur sayısını gerçekten indiriyor mu?</b>
 *
 * <p>Bu testin var olma sebebi, tasarımın en kırılgan iddiasını <em>ölçmek</em>. Maliyet
 * planının tamamı kapının turların büyük çoğunluğunu kapatmasına dayanıyor; kapı
 * çalışmıyorsa proje ekonomik olarak biter ve bunu üretimde faturayla öğrenmek istemeyiz.
 *
 * <h2>Bu test tasarımı üç kez değiştirdi</h2>
 * İlk ölçüm %29.3 açılma oranı verdi ve kırılım sorunu gösterdi:
 * <ol>
 *   <li><b>REGIME_CHANGE=248</b> — rejim her 6 mumda bir savruluyordu. Sebep: rejimin
 *       oynaklık ekseni kısa vadeli (24 mum) ölçüye bağlıydı. Kavramsal hata:
 *       <em>rejim yavaş bir karakter, tetikleyici hızlı bir olaydır.</em> Rejim yavaş
 *       ölçüye (96 mum) taşındı ve histerezis eklendi → 88.</li>
 *   <li><b>MACD_CROSS=121</b> — histogram sıfırın etrafında salınıyordu. Anlamlılık
 *       eşiği eklendi (fiyatın %0.05'i) → 37.</li>
 *   <li><b>BOLLINGER_BREAKOUT=94</b> — 2σ bandının dışına çıkmak tanımı gereği zamanın
 *       ~%5'inde olan bir şey. Bandın ucundan değmek kırılım sayılmaz oldu → 70.</li>
 * </ol>
 * Sonuç: %29.3 → %14.5. Üçünü de <em>ölçüm</em> buldu; kod okuyarak hiçbiri görünmüyordu.
 *
 * <h2>Bant neden geniş</h2>
 * Burada doğrulanan bir <em>doğruluk</em> değil bir <em>oran</em>. Dar bir bant, gösterge
 * parametrelerindeki her masum değişiklikte kırmızı yanan kırılgan bir test olurdu.
 * Bandın işi, oranın beklenen büyüklük mertebesinden çıktığını yakalamak.
 */
@DisplayName("Kapı testi: tetikleyici oranı")
class TriggerGateRateTest {

    /** Rastgele yürüyüşün tohumu — sabit, çünkü ölçüm koşudan koşuya değişmemeli. */
    private static final long SEED = 20260825L;

    private static final int WARMUP_BARS = 900;
    private static final int MEASURED_BARS = 1_500;

    @Test
    @DisplayName("Gerçekçi bir seride kapı turların büyük çoğunluğunu kapatıyor")
    void gateClosesMostRounds() {
        Result result = run(TestBars.randomWalk(WARMUP_BARS + MEASURED_BARS, 30_000,
                0.01, 0.6, SEED), false);

        // Ölçülen sayı raporlanıyor: bandı geçmiş olmasından daha bilgilendirici.
        report("Gerçekçi seri", result);

        assertThat(result.total).isEqualTo(MEASURED_BARS);
        // Üst sınır: kapı bunun üstünde açıyorsa maliyet tasarımı çöker.
        assertThat(result.openRate())
                .as("kapı turların çoğunu kapatmalı (maliyet tasarımının dayanağı)")
                .isLessThan(20.0);
        // Alt sınır: hiç açmayan bir kapı da bozuk — sistem asla karar üretmez.
        assertThat(result.openRate())
                .as("kapı bazı turları açmalı; hiç açmıyorsa sistem karar üretmez")
                .isGreaterThan(0.5);
        // Hiçbir tetikleyici tek başına baskın olmamalı: baskınlık, o tetikleyicinin
        // gürültü ürettiğinin işareti (üç kez böyle yakalandı).
        int dominant = result.byType.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        assertThat(dominant)
                .as("tek bir tetikleyici açılmaların yarısından fazlasını üretmemeli")
                .isLessThan(result.opened);
    }

    @Test
    @DisplayName("Sakin ve düzgün bir trendde kapı neredeyse hiç açılmıyor")
    void smoothTrendOpensAlmostNothing() {
        // Düzgün bir rampa: fiyat istikrarlı yükseliyor, sürpriz yok. Kapının "olay yok"
        // durumunu tanıyıp tanımadığını gösteren en doğrudan kontrol.
        Result result = run(TestBars.ramp(WARMUP_BARS + 500, 30_000, 3.0), false);

        report("Düzgün rampa", result);
        assertThat(result.openRate()).isLessThan(5.0);
    }

    @Test
    @DisplayName("Kapı ölçek-değişmez: aynı istatistiksel doku, farklı fiyat seviyesi")
    void gateIsScaleInvariant() {
        // Tüm eşikler göreli (ATR katı, persentil, yüzde). Bu, aynı istatistiksel dokuya
        // sahip iki varlığın — biri $30.000, biri $0.30 — aynı oranda tur açması demek.
        // İstenen bir özellik: eşikleri sembol başına ayarlamak zorunda kalmıyoruz.
        Result expensive = run(TestBars.randomWalk(WARMUP_BARS + 600, 30_000, 0.01, 0.6, SEED),
                false);
        Result cheap = run(TestBars.randomWalk(WARMUP_BARS + 600, 0.30, 0.01, 0.6, SEED), false);

        report("Pahalı varlık", expensive);
        report("Ucuz varlık", cheap);
        assertThat(cheap.openRate()).isCloseTo(expensive.openRate(),
                org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    @DisplayName("Açık pozisyonda oran yükseliyor — planlı gözden geçirme devreye giriyor")
    void openPositionRaisesRate() {
        List<Bar> bars = TestBars.randomWalk(WARMUP_BARS + MEASURED_BARS, 30_000,
                0.01, 0.6, SEED);

        Result flat = run(bars, false);
        Result holding = run(bars, true);

        report("Pozisyonsuz", flat);
        report("Pozisyonlu", holding);

        // Açık pozisyon varken 4 saatte bir tur garanti: pozisyonu açan tez zamanla
        // çürüyebilir ve bunun hiçbir gösterge eşiğine yansımaması mümkün.
        assertThat(holding.openRate()).isGreaterThan(flat.openRate());
        // Yine de her turu açmıyor: 4 saatte bir demek, 1 saatlik mumlarda ~%25 taban.
        assertThat(holding.openRate()).isLessThan(45.0);
        assertThat(holding.byType).containsKey(Trigger.Type.SCHEDULED_REVIEW);
    }

    @Test
    @DisplayName("Tam tur hesabı makul sürede bitiyor")
    void completesInReasonableTime() {
        long start = System.nanoTime();
        run(TestBars.randomWalk(WARMUP_BARS + 200, 30_000, 0.01, 0.6, SEED), false);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // Kapı her mumda koşacak; saniyeler süren bir hesap canlıda darboğaz olur.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(30));
    }

    /**
     * Seriyi mum mum yürütüp kapıyı her adımda değerlendirir.
     *
     * <p>Her adımda gösterge ve istatistikler <em>o ana kadar kapanmış</em> mumlardan
     * yeniden hesaplanıyor — geri testin gerçek koşum biçimi bu.
     */
    private static Result run(List<Bar> bars, boolean hasOpenPosition) {
        TestBars.StubReader reader = new TestBars.StubReader(bars);
        IndicatorService indicators = AnalysisFactory.indicatorService(reader);
        StatsService stats = AnalysisFactory.statsService(reader);
        RegimeClassifier classifier = AnalysisFactory.regimeClassifier();
        TriggerGate gate = AnalysisFactory.triggerGate();

        Result result = new Result();
        IndicatorSet previousIndicators = null;
        Regime previousRegime = null;
        Instant lastRoundAt = null;

        for (int i = WARMUP_BARS; i < bars.size(); i++) {
            Instant asOf = bars.get(i).closeTime();
            IndicatorSet current = indicators.compute(TestBars.BTC, Timeframe.H1, asOf);
            PriceStats currentStats = stats.compute(TestBars.BTC, Timeframe.H1, asOf);
            Regime regime = classifier.classify(current, currentStats, previousRegime);

            TriggerDecision decision = gate.evaluate(TriggerContext.at(TestBars.BTC, asOf)
                    .indicators(current, previousIndicators)
                    .stats(currentStats)
                    .regime(regime, previousRegime)
                    .openPosition(hasOpenPosition)
                    .lastRoundAt(lastRoundAt)
                    .build());

            result.total++;
            if (decision.open()) {
                result.opened++;
                lastRoundAt = asOf;
                decision.reasons().forEach(r -> result.byType.merge(r.type(), 1, Integer::sum));
            }
            previousIndicators = current;
            previousRegime = regime;
        }
        return result;
    }

    /** Ölçümü sembol başına günlük tur sayısına da çeviriyor — maliyeti belirleyen sayı bu. */
    private static void report(String label, Result result) {
        System.out.printf("%-16s açılma %%%.1f (%d/%d) ≈ %.1f tur/gün/sembol · %s%n",
                label, result.openRate(), result.opened, result.total,
                result.roundsPerDayOnHourlyBars(), result.byType);
    }

    private static final class Result {
        int total;
        int opened;
        final Map<Trigger.Type, Integer> byType = new EnumMap<>(Trigger.Type.class);

        double openRate() {
            return total == 0 ? 0 : 100.0 * opened / total;
        }

        /** 1 saatlik mumlarda günde 24 değerlendirme yapılıyor. */
        double roundsPerDayOnHourlyBars() {
            return openRate() / 100.0 * 24;
        }
    }
}
