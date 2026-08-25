package com.investor.marketdata;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.investor.marketdata.internal.MarketDataTestAccess;
import com.investor.marketdata.model.Bar;
import com.investor.marketdata.model.Gap;
import com.investor.marketdata.model.Timeframe;
import com.investor.marketdata.support.AbstractMarketDataTest;
import com.investor.marketdata.support.BarFixtures;
import com.investor.marketdata.support.StubMarketDataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Boşluk tespiti, doldurma ve tazelik")
@Import(GapAndFreshnessTest.StubSourceConfiguration.class)
class GapAndFreshnessTest extends AbstractMarketDataTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class StubSourceConfiguration {
        @Bean
        @Primary
        StubMarketDataSource stubSource() {
            return new StubMarketDataSource(Instant.parse("2026-03-15T12:00:00Z"));
        }
    }

    @Autowired
    private MarketDataIngest ingest;

    @Autowired
    private MarketDataTestAccess access;

    @Autowired
    private StubMarketDataSource source;

    @Test
    @DisplayName("ardışık eksik mumlar tek boşluk olarak gruplanır")
    void consecutiveMissingBarsFormOneGap() {
        List<Bar> minutes = new ArrayList<>(BarFixtures.minuteBars(btcusdt, T0, 20));
        // 3-4-5 ardışık; 12 tek başına
        minutes.remove(12);
        minutes.remove(5);
        minutes.remove(4);
        minutes.remove(3);
        access.write(minutes);

        Instant to = T0.plus(Duration.ofMinutes(20));
        List<Gap> gaps = reader.findGaps(btcusdt, Timeframe.M1, T0, to);

        assertThat(gaps).hasSize(2);
        assertThat(gaps.get(0).fromInclusive()).isEqualTo(T0.plus(Duration.ofMinutes(3)));
        assertThat(gaps.get(0).toExclusive()).isEqualTo(T0.plus(Duration.ofMinutes(6)));
        assertThat(gaps.get(0).missingBars()).isEqualTo(3);
        assertThat(gaps.get(1).fromInclusive()).isEqualTo(T0.plus(Duration.ofMinutes(12)));
        assertThat(gaps.get(1).missingBars()).isEqualTo(1);
    }

    @Test
    @DisplayName("boşluk doldurma kaynaktan yalnızca eksik aralıkları çeker")
    void fillGapsFetchesOnlyMissingRanges() {
        List<Bar> minutes = new ArrayList<>(BarFixtures.minuteBars(btcusdt, T0, 20));
        minutes.remove(7);
        access.write(minutes);

        Instant to = T0.plus(Duration.ofMinutes(20));
        source.resetRequestCount();

        int filled = ingest.fillGaps(btcusdt, Timeframe.M1, T0, to);

        assertThat(filled).isEqualTo(1);
        assertThat(source.requestCount())
                .as("tek boşluk için tek istek yeter; tüm aralık yeniden çekilmemeli")
                .isEqualTo(1);
        assertThat(reader.findGaps(btcusdt, Timeframe.M1, T0, to)).isEmpty();
    }

    @Test
    @DisplayName("boşluk yoksa hiç istek yapılmaz")
    void noGapsMeansNoRequests() {
        access.write(BarFixtures.minuteBars(btcusdt, T0, 10));
        source.resetRequestCount();

        assertThat(ingest.fillGaps(btcusdt, Timeframe.M1, T0, T0.plus(Duration.ofMinutes(10)))).isZero();
        assertThat(source.requestCount()).isZero();
    }

    @Test
    @DisplayName("kapanmamış mum boşluk sayılır")
    void nonFinalBarCountsAsGap() {
        List<Bar> minutes = new ArrayList<>(BarFixtures.minuteBars(btcusdt, T0, 5));
        Bar third = minutes.get(2);
        minutes.set(2, new Bar(third.instrumentId(), third.timeframe(), third.openTime(), third.closeTime(),
                third.open(), third.high(), third.low(), third.close(), third.volume(), third.quoteVolume(),
                third.tradeCount(), third.takerBuyBase(), false));
        access.write(minutes);

        assertThat(reader.findGaps(btcusdt, Timeframe.M1, T0, T0.plus(Duration.ofMinutes(5))))
                .as("kapanmamış mum, karar üretimi açısından yok hükmünde")
                .singleElement()
                .satisfies(gap -> assertThat(gap.fromInclusive()).isEqualTo(T0.plus(Duration.ofMinutes(2))));
    }

    @Test
    @DisplayName("backfill sayfalama yapar ve idempotenttir")
    void backfillPaginatesAndIsIdempotent() {
        source.setMaxBarsPerRequest(10);
        source.resetRequestCount();
        Instant to = T0.plus(Duration.ofMinutes(35));

        int written = ingest.backfill(btcusdt, Timeframe.M1, T0, to);

        assertThat(written).isEqualTo(35);
        assertThat(source.requestCount()).isEqualTo(4);   // 10+10+10+5
        assertThat(reader.finalBars(btcusdt, Timeframe.M1, T0, to)).hasSize(35);

        int again = ingest.backfill(btcusdt, Timeframe.M1, T0, to);
        assertThat(again).isEqualTo(35);
        assertThat(reader.finalBars(btcusdt, Timeframe.M1, T0, to))
                .as("tekrar çekmek çift kayıt üretmemeli")
                .hasSize(35);
    }

    @Test
    @DisplayName("tazelik gecikmeyi ölçer ve eşik aşılınca bayat der")
    void freshnessReportsStaleness() {
        access.write(BarFixtures.minuteBars(btcusdt, T0, 10));
        Instant lastOpen = T0.plus(Duration.ofMinutes(9));

        // Son mumdan 2 dakika sonra: gecikme yok sayılır (tolerans 5 dk)
        var fresh = reader.freshness(btcusdt, Timeframe.M1, lastOpen.plus(Duration.ofMinutes(2)));
        assertThat(fresh.lastFinalOpenTime()).isEqualTo(lastOpen);
        assertThat(fresh.stale()).isFalse();

        // 30 dakika sonra: veri akmıyor
        var stale = reader.freshness(btcusdt, Timeframe.M1, lastOpen.plus(Duration.ofMinutes(30)));
        assertThat(stale.staleness()).isGreaterThan(Duration.ofMinutes(5));
        assertThat(stale.stale())
                .as("bayat veriyle taze veri gibi konuşmak sistemin yapabileceği en sinsi hata")
                .isTrue();

        // Hiç veri yoksa: sonsuz bayat
        var missing = reader.freshness(btcusdt, Timeframe.H4, T0);
        assertThat(missing.stale()).isTrue();
        assertThat(missing.lastFinalOpen()).isEmpty();
    }
}
