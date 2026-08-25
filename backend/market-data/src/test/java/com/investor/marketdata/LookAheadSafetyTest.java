package com.investor.marketdata;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.investor.marketdata.internal.MarketDataTestAccess;
import com.investor.marketdata.model.Bar;
import com.investor.marketdata.model.Timeframe;
import com.investor.marketdata.support.AbstractMarketDataTest;
import com.investor.marketdata.support.BarFixtures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Okuma API'sinin geleceği sızdırmadığının kanıtı.
 *
 * <p>Bu, ontolojideki bitemporal kapı testinin piyasa verisi tarafındaki karşılığı.
 * Aynı sessiz hata sınıfını hedefliyor: backtest sırasında henüz olmamış bir mumu görmek
 * hiçbir yerde hata olarak görünmez — sadece sonuçları gerçekte olduğundan iyi gösterir.
 */
@DisplayName("Look-ahead güvenliği")
class LookAheadSafetyTest extends AbstractMarketDataTest {

    @Autowired
    private MarketDataTestAccess access;

    @Test
    @DisplayName("lastFinalBars, asOf anında henüz kapanmamış mumu vermez")
    void lastFinalBarsStopsAtAsOf() {
        access.write(BarFixtures.minuteBars(btcusdt, T0, 100));

        // T0+50dk anında: [T0+49m, T0+50m) yeni kapandı, [T0+50m, T0+51m) yeni açıldı.
        Instant asOf = T0.plus(Duration.ofMinutes(50));
        List<Bar> bars = reader.lastFinalBars(btcusdt, Timeframe.M1, 10, asOf);

        assertThat(bars).hasSize(10);
        assertThat(bars.get(0).openTime()).isEqualTo(T0.plus(Duration.ofMinutes(40)));
        assertThat(bars.get(9).openTime())
                .as("asOf anında kapanmış son mum")
                .isEqualTo(T0.plus(Duration.ofMinutes(49)));
        assertThat(bars)
                .as("geleceğe ait tek bir mum bile gelmemeli")
                .allSatisfy(bar -> assertThat(bar.closeTime()).isBefore(asOf));
    }

    @Test
    @DisplayName("mumun ortasında sorulduğunda o mum henüz kapanmamıştır")
    void barInProgressIsNotReturned() {
        access.write(BarFixtures.minuteBars(btcusdt, T0, 10));

        // T0+5dk30sn: [T0+5m, T0+6m) mumu hâlâ açık.
        Instant midBar = T0.plus(Duration.ofMinutes(5)).plusSeconds(30);
        List<Bar> bars = reader.lastFinalBars(btcusdt, Timeframe.M1, 3, midBar);

        assertThat(bars.get(2).openTime())
                .as("açık mum değil, ondan önceki dönmeli")
                .isEqualTo(T0.plus(Duration.ofMinutes(4)));
        assertThat(reader.lastFinalOpenTime(btcusdt, Timeframe.M1, midBar))
                .contains(T0.plus(Duration.ofMinutes(4)));
    }

    @Test
    @DisplayName("kapanmamış mumlar hiçbir okuma yolundan çıkmaz")
    void nonFinalBarsAreInvisible() {
        List<Bar> minutes = new java.util.ArrayList<>(BarFixtures.minuteBars(btcusdt, T0, 5));
        Bar last = minutes.get(4);
        minutes.set(4, new Bar(last.instrumentId(), last.timeframe(), last.openTime(), last.closeTime(),
                last.open(), last.high(), last.low(), last.close(), last.volume(), last.quoteVolume(),
                last.tradeCount(), last.takerBuyBase(), false));
        access.write(minutes);

        Instant wellAfter = T0.plus(Duration.ofHours(1));
        assertThat(reader.finalBars(btcusdt, Timeframe.M1, T0, wellAfter)).hasSize(4);
        assertThat(reader.lastFinalBars(btcusdt, Timeframe.M1, 10, wellAfter)).hasSize(4);
        assertThat(reader.finalBarAt(btcusdt, Timeframe.M1, last.openTime())).isEmpty();
        assertThat(reader.lastFinalOpenTime(btcusdt, Timeframe.M1, wellAfter))
                .contains(T0.plus(Duration.ofMinutes(3)));
    }

    @Test
    @DisplayName("aralık üst sınırı dahil değildir")
    void rangeUpperBoundIsExclusive() {
        access.write(BarFixtures.minuteBars(btcusdt, T0, 10));

        List<Bar> bars = reader.finalBars(btcusdt, Timeframe.M1, T0, T0.plus(Duration.ofMinutes(5)));
        assertThat(bars).hasSize(5);
        assertThat(bars.get(4).openTime()).isEqualTo(T0.plus(Duration.ofMinutes(4)));
    }

    @Test
    @DisplayName("geçersiz aralık ve mum sayısı reddedilir")
    void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> reader.finalBars(btcusdt, Timeframe.M1, T0, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sonra olmalı");

        assertThatThrownBy(() -> reader.lastFinalBars(btcusdt, Timeframe.M1, 0, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pozitif");
    }

    @Test
    @DisplayName("üst zaman dilimlerinde de asOf sınırı geçerli")
    void asOfBoundHoldsForDerivedTimeframes() {
        access.write(BarFixtures.minuteBars(btcusdt, T0, 120));
        Instant to = T0.plus(Duration.ofHours(2));

        // 15m rollup'ları üret
        var ingestBean = applicationIngest();
        ingestBean.rollup(btcusdt, Timeframe.M15, T0, to);

        Instant asOf = T0.plus(Duration.ofMinutes(47));
        List<Bar> bars = reader.lastFinalBars(btcusdt, Timeframe.M15, 5, asOf);

        assertThat(bars).hasSize(3);   // 0-15, 15-30, 30-45 kapandı; 45-60 açık
        assertThat(bars.get(2).openTime()).isEqualTo(T0.plus(Duration.ofMinutes(30)));
    }

    @Autowired
    private MarketDataIngest ingest;

    private MarketDataIngest applicationIngest() {
        return ingest;
    }
}
