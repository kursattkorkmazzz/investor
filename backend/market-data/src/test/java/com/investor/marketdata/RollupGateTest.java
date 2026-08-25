package com.investor.marketdata;

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

/**
 * Faz-2 kapı testi.
 *
 * <p>İki iddia var. Birincisi: 1m'den türetilen üst zaman dilimleri doğru hesaplanıyor —
 * SQL'in sonucu, Java'da bağımsız hesaplanan sonuçla birebir aynı. İkincisi ve daha
 * önemlisi: eksik taban mumu olan bir kova <em>hiç yazılmıyor</em>.
 *
 * <p>İkincisi neden daha önemli: eksik 1m'den üretilmiş bir 15m mum makul görünür, grafiği
 * normal çizilir, indikatörü hesaplanır — ve yanlıştır. Hata hiçbir yerde hata olarak
 * görünmediği için kararlara sessizce sızar.
 */
@DisplayName("Kapı testi: rollup doğruluğu")
class RollupGateTest extends AbstractMarketDataTest {

    @Autowired
    private MarketDataIngest ingest;

    @Autowired
    private MarketDataTestAccess access;

    @Test
    @DisplayName("üst zaman dilimleri bağımsız hesapla birebir eşleşiyor")
    void rollupsMatchIndependentComputation() {
        List<Bar> minutes = BarFixtures.minuteBars(btcusdt, T0, 60 * 4);
        access.write(minutes);

        Instant to = T0.plus(Timeframe.M1.duration().multipliedBy(minutes.size()));

        for (Timeframe target : List.of(Timeframe.M5, Timeframe.M15, Timeframe.H1)) {
            int written = ingest.rollup(btcusdt, target, T0, to);
            int expectedBuckets = minutes.size() / target.baseBarCount();
            assertThat(written)
                    .as("%s için beklenen kova sayısı", target.code())
                    .isEqualTo(expectedBuckets);

            List<Bar> produced = reader.finalBars(btcusdt, target, T0, to);
            assertThat(produced).hasSize(expectedBuckets);

            for (int bucket = 0; bucket < expectedBuckets; bucket++) {
                int from = bucket * target.baseBarCount();
                List<Bar> sources = minutes.subList(from, from + target.baseBarCount());
                Instant bucketStart = T0.plus(target.duration().multipliedBy(bucket));
                Bar expected = BarFixtures.expectedAggregate(sources, target, bucketStart);
                Bar actual = produced.get(bucket);

                assertThat(actual.openTime()).isEqualTo(expected.openTime());
                assertThat(actual.open()).isEqualByComparingTo(expected.open());
                assertThat(actual.high()).isEqualByComparingTo(expected.high());
                assertThat(actual.low()).isEqualByComparingTo(expected.low());
                assertThat(actual.close()).isEqualByComparingTo(expected.close());
                assertThat(actual.volume()).isEqualByComparingTo(expected.volume());
                assertThat(actual.quoteVolume()).isEqualByComparingTo(expected.quoteVolume());
                assertThat(actual.takerBuyBase()).isEqualByComparingTo(expected.takerBuyBase());
                assertThat(actual.tradeCount()).isEqualTo(expected.tradeCount());
                assertThat(actual.isFinal()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("eksik taban mumu olan kova hiç yazılmaz")
    void bucketWithMissingBaseBarIsNotWritten() {
        List<Bar> minutes = BarFixtures.minuteBars(btcusdt, T0, 15);
        // 8. dakika hiç gelmemiş: WebSocket kopması, borsa bakımı, ne olursa.
        List<Bar> withHole = new java.util.ArrayList<>(minutes);
        withHole.remove(8);
        access.write(withHole);

        Instant to = T0.plus(Timeframe.M1.duration().multipliedBy(15));

        int written = ingest.rollup(btcusdt, Timeframe.M5, T0, to);
        assertThat(written)
                .as("üç 5m kovasından yalnızca ikisi tam veriye sahip")
                .isEqualTo(2);

        List<Bar> produced = reader.finalBars(btcusdt, Timeframe.M5, T0, to);
        assertThat(produced).extracting(Bar::openTime).containsExactly(
                T0,
                T0.plus(Timeframe.M5.duration().multipliedBy(2)));
        assertThat(produced)
                .as("eksik veriden mum üretilmemeli — yanlış bir mum, hiç mumdan kötüdür")
                .noneMatch(bar -> bar.openTime().equals(T0.plus(Timeframe.M5.duration())));
    }

    @Test
    @DisplayName("boşluk doldurulduktan sonra rollup doğru mumu üretir")
    void rollupSucceedsAfterGapIsFilled() {
        List<Bar> minutes = BarFixtures.minuteBars(btcusdt, T0, 5);
        List<Bar> withHole = new java.util.ArrayList<>(minutes);
        withHole.remove(2);
        access.write(withHole);

        Instant to = T0.plus(Timeframe.M1.duration().multipliedBy(5));
        assertThat(ingest.rollup(btcusdt, Timeframe.M5, T0, to)).isZero();

        // Eksik mum sonradan geliyor
        access.write(List.of(minutes.get(2)));
        assertThat(ingest.rollup(btcusdt, Timeframe.M5, T0, to)).isEqualTo(1);

        Bar expected = BarFixtures.expectedAggregate(minutes, Timeframe.M5, T0);
        Bar actual = reader.finalBarAt(btcusdt, Timeframe.M5, T0).orElseThrow();
        assertThat(actual.high()).isEqualByComparingTo(expected.high());
        assertThat(actual.low()).isEqualByComparingTo(expected.low());
        assertThat(actual.close()).isEqualByComparingTo(expected.close());
        assertThat(actual.volume()).isEqualByComparingTo(expected.volume());
    }

    @Test
    @DisplayName("kapanmamış taban mum rollup'a girmez")
    void nonFinalBaseBarsAreExcluded() {
        List<Bar> minutes = new java.util.ArrayList<>(BarFixtures.minuteBars(btcusdt, T0, 5));
        Bar last = minutes.get(4);
        minutes.set(4, new Bar(last.instrumentId(), last.timeframe(), last.openTime(), last.closeTime(),
                last.open(), last.high(), last.low(), last.close(), last.volume(), last.quoteVolume(),
                last.tradeCount(), last.takerBuyBase(), false));
        access.write(minutes);

        Instant to = T0.plus(Timeframe.M1.duration().multipliedBy(5));
        assertThat(ingest.rollup(btcusdt, Timeframe.M5, T0, to))
                .as("beş mumun biri kapanmamışsa 5m mum üretilemez")
                .isZero();
    }

    @Test
    @DisplayName("rollup idempotent: iki kez koşmak sonucu değiştirmez")
    void rollupIsIdempotent() {
        access.write(BarFixtures.minuteBars(btcusdt, T0, 30));
        Instant to = T0.plus(Timeframe.M1.duration().multipliedBy(30));

        ingest.rollup(btcusdt, Timeframe.M15, T0, to);
        List<Bar> first = reader.finalBars(btcusdt, Timeframe.M15, T0, to);

        ingest.rollup(btcusdt, Timeframe.M15, T0, to);
        List<Bar> second = reader.finalBars(btcusdt, Timeframe.M15, T0, to);

        assertThat(second).hasSameSizeAs(first);
        for (int i = 0; i < first.size(); i++) {
            assertThat(second.get(i).close()).isEqualByComparingTo(first.get(i).close());
            assertThat(second.get(i).volume()).isEqualByComparingTo(first.get(i).volume());
        }
    }

    @Test
    @DisplayName("taban zaman dilimi türetilemez")
    void baseTimeframeCannotBeRolledUp() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ingest.rollup(btcusdt, Timeframe.M1, T0, T0.plusSeconds(3600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taban");
    }
}
