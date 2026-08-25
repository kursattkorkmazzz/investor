package com.investor.analysis.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.investor.analysis.Descriptives;
import com.investor.analysis.model.PriceStats;
import com.investor.analysis.support.TestBars;
import com.investor.marketdata.model.Bar;
import com.investor.marketdata.model.Timeframe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** İstatistik servisi ve betimsel fonksiyonların testleri. */
class DefaultStatsServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Nested
    @DisplayName("Betimsel fonksiyonlar")
    class DescriptivesTest {

        @Test
        @DisplayName("Persentil sırası eşit değerleri yarım sayıyor")
        void percentileRankHalvesTies() {
            double[] values = {1, 2, 3, 4, 5};

            // 3 için: 2 küçük + 1 eşitin yarısı = 2.5 / 5 = %50
            assertThat(Descriptives.percentileRank(values, 3).orElseThrow())
                    .isCloseTo(50.0, within(1e-9));
            assertThat(Descriptives.percentileRank(values, 0).orElseThrow()).isZero();
            assertThat(Descriptives.percentileRank(values, 99).orElseThrow()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("Sapma sıfırken z-skor tanımsız — sıfır değil")
        void zScoreUndefinedWithoutVariance() {
            // Sıfır dönseydi "tam ortalamada" gibi okunurdu; doğru okuma "z-skor anlamsız".
            assertThat(Descriptives.zScore(new double[]{5, 5, 5}, 5)).isEmpty();
            assertThat(Descriptives.zScore(new double[]{5}, 5)).isEmpty();
        }

        @Test
        @DisplayName("z-skor bilinen bir örneklemde doğru")
        void zScoreMatchesKnownSample() {
            double[] values = {2, 4, 4, 4, 5, 5, 7, 9};
            // ortalama 5, örneklem sapması (n-1) = 2.13809...
            double sd = Descriptives.standardDeviation(values);

            assertThat(Descriptives.zScore(values, 9).orElseThrow())
                    .isCloseTo((9 - 5) / sd, within(1e-12));
        }

        @Test
        @DisplayName("Log getiri simetrik: yarıya inip ikiye katlamak sıfır toplam")
        void logReturnsAreSymmetric() {
            double[] returns = Descriptives.logReturns(new double[]{100, 50, 100});

            assertThat(returns).hasSize(2);
            // Basit getiride bu -%50 ve +%100 olurdu; toplamı sıfır olmazdı.
            assertThat(returns[0] + returns[1]).isCloseTo(0.0, within(1e-12));
        }

        @Test
        @DisplayName("Sıfır ve negatif fiyatlı çiftler atlanıyor")
        void logReturnsSkipNonPositive() {
            assertThat(Descriptives.logReturns(new double[]{100, 0, 100, 110})).hasSize(1);
        }
    }

    @Nested
    @DisplayName("İstatistik servisi")
    class Service {

        @Test
        @DisplayName("Getiri yüzdesi bağımsız hesapla uyuyor")
        void returnMatchesIndependentCalculation() {
            List<Bar> bars = TestBars.ramp(400, 100, 0.5);
            PriceStats stats = service(bars).compute(TestBars.BTC, Timeframe.H1,
                    T0.plus(Duration.ofHours(500)));

            double current = bars.get(bars.size() - 1).close().doubleValue();
            double past = bars.get(bars.size() - 1 - 24).close().doubleValue();

            assertThat(stats.value("return24").orElseThrow().doubleValue())
                    .isCloseTo((current / past - 1) * 100, within(1e-6));
        }

        @Test
        @DisplayName("Oynaklık yıllıklandırılmış — zaman dilimi değişince ölçek değişmiyor")
        void volatilityIsAnnualized() {
            // Aynı yüzdesel salınım, iki farklı zaman diliminde. Yıllıklandırma doğruysa
            // saatlik seri günlük seriden ~sqrt(24) kat yüksek yillik oynaklik verir.
            PriceStats hourly = service(TestBars.oscillating(400, 100, 0.01))
                    .compute(TestBars.BTC, Timeframe.H1, T0.plus(Duration.ofHours(500)));
            PriceStats daily = service(TestBars.oscillating(400, 100, 0.01))
                    .compute(TestBars.BTC, Timeframe.D1, T0.plus(Duration.ofHours(500)));

            double ratio = hourly.value("realizedVol").orElseThrow().doubleValue()
                    / daily.value("realizedVol").orElseThrow().doubleValue();

            assertThat(ratio).isCloseTo(Math.sqrt(24), within(0.01));
        }

        @Test
        @DisplayName("Hacim z-skoru ani sıçramayı yakalıyor")
        void volumeZScoreDetectsSpike() {
            List<Bar> bars = new java.util.ArrayList<>(TestBars.ramp(200, 100, 0.1));
            // Son mumun hacmini on katına çıkar.
            Bar last = bars.get(bars.size() - 1);
            bars.set(bars.size() - 1, TestBars.withVolume(last, 100));

            PriceStats stats = service(bars).compute(TestBars.BTC, Timeframe.H1,
                    T0.plus(Duration.ofHours(300)));

            assertThat(stats.value("volumeZScore").orElseThrow().doubleValue()).isGreaterThan(3);
        }

        @Test
        @DisplayName("Şimdiki hacim kendi karşılaştırma penceresine dahil değil")
        void volumeZScoreExcludesCurrentBar() {
            List<Bar> bars = new java.util.ArrayList<>(TestBars.ramp(60, 100, 0.1));
            Bar last = bars.get(bars.size() - 1);
            bars.set(bars.size() - 1, TestBars.withVolume(last, 1000));

            PriceStats stats = service(bars).compute(TestBars.BTC, Timeframe.H1,
                    T0.plus(Duration.ofHours(100)));

            // Karşılaştırma penceresi 24 mum; şimdiki mum dışarıda olduğu için gözlem
            // sayısı da 24. İçeride olsaydı 25 olur ve sıçrama ortalamayı yukarı
            // çekerek kendi anomalisini gizlerdi.
            assertThat(stats.get("volumeZScore").orElseThrow().sampleSize()).isEqualTo(24);
        }

        @Test
        @DisplayName("Yükselen seride fiyat persentili tepede")
        void pricePercentileAtTopOnUptrend() {
            PriceStats stats = service(TestBars.ramp(300, 100, 1))
                    .compute(TestBars.BTC, Timeframe.H1, T0.plus(Duration.ofHours(400)));

            assertThat(stats.value("pricePercentile").orElseThrow().doubleValue())
                    .isGreaterThan(99);
        }

        @Test
        @DisplayName("Yetersiz gözlemde oynaklık persentili hiç üretilmiyor")
        void volPercentileNeedsEnoughSamples() {
            // 30 mum: kısa pencere (24) bir kez doluyor, kayan pencere serisi 7 gözlem —
            // MIN_VOL_SAMPLES'ın altında.
            PriceStats stats = service(TestBars.ramp(30, 100, 0.3))
                    .compute(TestBars.BTC, Timeframe.H1, T0.plus(Duration.ofHours(100)));

            assertThat(stats.has("realizedVol")).isTrue();
            assertThat(stats.has("volPercentile")).isFalse();
        }

        @Test
        @DisplayName("İki mumdan az veriyle boş küme döner")
        void emptyWithoutEnoughBars() {
            assertThat(service(TestBars.ramp(1, 100, 1))
                    .compute(TestBars.BTC, Timeframe.H1, T0.plus(Duration.ofHours(10)))
                    .isEmpty()).isTrue();
        }

        @Test
        @DisplayName("Aynı asOf ile iki çağrı aynı sonucu veriyor")
        void isDeterministic() {
            List<Bar> bars = TestBars.ramp(400, 100, 0.4);
            Instant asOf = T0.plus(Duration.ofHours(500));

            assertThat(service(bars).compute(TestBars.BTC, Timeframe.H1, asOf).values())
                    .isEqualTo(service(bars).compute(TestBars.BTC, Timeframe.H1, asOf).values());
        }

        private DefaultStatsService service(List<Bar> bars) {
            return new DefaultStatsService(new TestBars.StubReader(bars));
        }
    }
}
