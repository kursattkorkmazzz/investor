package com.investor.analysis.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.investor.analysis.model.IndicatorSet;
import com.investor.analysis.support.TestBars;
import com.investor.marketdata.MarketDataReader;
import com.investor.marketdata.model.Bar;
import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.Timeframe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Gösterge servisi testleri.
 *
 * <p>Sahte bir {@link MarketDataReader} kullanıyor: buradaki soru veritabanının doğru
 * okuduğu değil (onu market-data testleri kanıtlıyor), hesabın doğru ve
 * <em>tekrarlanabilir</em> olduğu.
 *
 * <p>Kritik testler ta4j'nin çıktısını <b>bağımsız bir Java hesabıyla</b> karşılaştırıyor.
 * "Kütüphane doğru hesaplar" varsayımıyla geçilseydi, seriyi yanlış kurduğumuz (ör. mumları
 * ters sırada verdiğimiz) durumda test yine yeşil kalırdı.
 */
class Ta4jIndicatorServiceTest {

    private static final InstrumentRef BTC = TestBars.BTC;
    private static final Instant T0 = TestBars.T0;

    @Test
    @DisplayName("SMA tabanlı Bollinger orta bandı bağımsız hesapla birebir uyuyor")
    void bollingerMiddleMatchesIndependentSma() {
        List<Bar> bars = TestBars.ramp(300, 100, 1);
        IndicatorSet set = service(bars).compute(BTC, Timeframe.H1, T0.plus(Duration.ofHours(400)));

        // Bağımsız hesap: son 20 kapanışın aritmetik ortalaması.
        BigDecimal expected = bars.subList(bars.size() - 20, bars.size()).stream()
                .map(Bar::close)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(20), 10, RoundingMode.HALF_UP);

        assertThat(set.value("bbMiddle")).isPresent();
        assertThat(set.value("bbMiddle").orElseThrow().doubleValue())
                .isCloseTo(expected.doubleValue(), within(1e-8));
    }

    @Test
    @DisplayName("EMA bağımsız özyinelemeli hesapla uyuyor — seri sırası doğru")
    void emaMatchesIndependentRecursion() {
        List<Bar> bars = TestBars.ramp(300, 100, 0.5);
        IndicatorSet set = service(bars).compute(BTC, Timeframe.H1, T0.plus(Duration.ofHours(400)));

        // Bağımsız EMA(20): ta4j ilk değeri seri başındaki kapanışla tohumluyor.
        double alpha = 2.0 / (20 + 1);
        double ema = bars.get(0).close().doubleValue();
        for (int i = 1; i < bars.size(); i++) {
            ema = bars.get(i).close().doubleValue() * alpha + ema * (1 - alpha);
        }

        // Mumlar ters sırada verilseydi bu iddia düşerdi — testin asıl işi bu.
        assertThat(set.value("ema20").orElseThrow().doubleValue()).isCloseTo(ema, within(1e-6));
    }

    @Test
    @DisplayName("Isınmamış gösterge üretilmez, adı unavailable'a yazılır")
    void insufficientWarmupOmitsIndicator() {
        // 30 mum: SMA(20) için yeter, EMA(200) için yetmez, RSI(14) için 4×14=56 gerekiyor.
        IndicatorSet set = service(TestBars.ramp(30, 100, 1))
                .compute(BTC, Timeframe.H1, T0.plus(Duration.ofHours(100)));

        assertThat(set.has("bbMiddle")).as("SMA(20) 30 mumla hesaplanabilir").isTrue();
        assertThat(set.has("rsi14")).as("RSI(14) 4x14=56 mum ister").isFalse();
        assertThat(set.has("ema200")).isFalse();
        assertThat(set.unavailable()).contains("rsi14", "ema200", "ema50");
        // Türetilmiş göstergeler de kaynakları yoksa üretilmiyor.
        assertThat(set.unavailable()).contains("macdHistogram", "atrPercent");
    }

    @Test
    @DisplayName("Yeterli ısınmada gösterge geliyor ve kaç mum kullanıldığı görünüyor")
    void sufficientWarmupProducesIndicator() {
        IndicatorSet set = service(TestBars.ramp(900, 100, 0.1))
                .compute(BTC, Timeframe.H1, T0.plus(Duration.ofHours(1000)));

        assertThat(set.has("ema200")).isTrue();
        assertThat(set.get("ema200").orElseThrow().barsUsed()).isEqualTo(800);
        // Hesap tarifi istemin içine giriyor; boş kalmamalı.
        assertThat(set.get("ema200").orElseThrow().method())
                .contains("EMA(200)").contains("1h");
    }

    @Test
    @DisplayName("Sürekli yükselen seride RSI aşırı alım bölgesinde")
    void rsiSaturatesOnMonotonicRise() {
        IndicatorSet set = service(TestBars.ramp(200, 100, 1))
                .compute(BTC, Timeframe.H1, T0.plus(Duration.ofHours(300)));

        // Hiç düşüş yoksa RSI 100'e yakınsar. Bu, hesabın yönünü doğrulayan en basit kontrol:
        // seriyi ters kursaydık 0'a yakınsardı.
        assertThat(set.value("rsi14").orElseThrow().doubleValue()).isGreaterThan(95);
    }

    @Test
    @DisplayName("Sürekli düşen seride RSI aşırı satım bölgesinde")
    void rsiSaturatesOnMonotonicFall() {
        IndicatorSet set = service(TestBars.ramp(200, 300, -1))
                .compute(BTC, Timeframe.H1, T0.plus(Duration.ofHours(300)));

        assertThat(set.value("rsi14").orElseThrow().doubleValue()).isLessThan(5);
    }

    @Test
    @DisplayName("Aynı asOf ile iki çağrı birebir aynı sonucu veriyor")
    void isDeterministic() {
        List<Bar> bars = TestBars.ramp(400, 100, 0.7);
        Instant asOf = T0.plus(Duration.ofHours(500));

        IndicatorSet first = service(bars).compute(BTC, Timeframe.H1, asOf);
        IndicatorSet second = service(bars).compute(BTC, Timeframe.H1, asOf);

        // Geri testin anlamlı olmasının ön koşulu: aynı geçmiş gün iki kez oynatıldığında
        // iki farklı sonuç çıkmamalı.
        assertThat(first.values()).isEqualTo(second.values());
    }

    @Test
    @DisplayName("asOf okuyucuya aynen geçiyor — geleceğe uzanma imkânı yok")
    void passesAsOfToReader() {
        AtomicReference<Instant> seen = new AtomicReference<>();
        MarketDataReader spy = new TestBars.StubReader(TestBars.ramp(300, 100, 1)) {
            @Override
            public List<Bar> lastFinalBars(InstrumentRef i, Timeframe tf, int count, Instant asOf) {
                seen.set(asOf);
                return super.lastFinalBars(i, tf, count, asOf);
            }
        };
        Instant asOf = T0.plus(Duration.ofHours(123));

        new Ta4jIndicatorService(spy).compute(BTC, Timeframe.H1, asOf);

        assertThat(seen.get()).isEqualTo(asOf);
    }

    @Test
    @DisplayName("Mum yoksa boş küme döner, patlamaz")
    void emptyWhenNoBars() {
        IndicatorSet set = service(List.of()).compute(BTC, Timeframe.H1, T0);

        assertThat(set.isEmpty()).isTrue();
        assertThat(set.barsAvailable()).isZero();
        assertThat(set.lastBarOpenTime()).isNull();
    }

    @Test
    @DisplayName("atrPercent varlıklar arası karşılaştırılabilir — fiyat ölçeğinden bağımsız")
    void atrPercentIsScaleInvariant() {
        // Aynı yüzdesel hareket, iki farklı fiyat ölçeğinde.
        IndicatorSet cheap = service(TestBars.oscillating(300, 100, 0.02)).compute(BTC, Timeframe.H1,
                T0.plus(Duration.ofHours(400)));
        IndicatorSet pricey = service(TestBars.oscillating(300, 100_000, 0.02)).compute(BTC, Timeframe.H1,
                T0.plus(Duration.ofHours(400)));

        // Çıplak ATR bin kat farklı; oranı aynı olmalı. Bu olmadan LLM'e "ATR 2400" demek
        // hiçbir şey ifade etmez.
        assertThat(cheap.value("atrPercent").orElseThrow().doubleValue())
                .isCloseTo(pricey.value("atrPercent").orElseThrow().doubleValue(), within(0.01));
    }

    @Test
    @DisplayName("bbPercentB fiyatın bantlar içindeki konumunu veriyor")
    void percentBLocatesPriceInBands() {
        IndicatorSet set = service(TestBars.ramp(300, 100, 1))
                .compute(BTC, Timeframe.H1, T0.plus(Duration.ofHours(400)));

        double upper = set.value("bbUpper").orElseThrow().doubleValue();
        double lower = set.value("bbLower").orElseThrow().doubleValue();
        double close = set.value("close").orElseThrow().doubleValue();
        double expected = (close - lower) / (upper - lower);

        assertThat(set.value("bbPercentB").orElseThrow().doubleValue())
                .isCloseTo(expected, within(1e-6));
    }

    private static Ta4jIndicatorService service(List<Bar> bars) {
        return new Ta4jIndicatorService(new TestBars.StubReader(bars));
    }
}
