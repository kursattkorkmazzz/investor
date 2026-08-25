package com.investor.analysis.support;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.investor.marketdata.MarketDataReader;
import com.investor.marketdata.model.Bar;
import com.investor.marketdata.model.Freshness;
import com.investor.marketdata.model.Gap;
import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.Timeframe;

/**
 * Analiz testleri için sentetik mum serileri ve sahte okuyucu.
 *
 * <p>Seriler kasten <em>basit ve tahmin edilebilir</em>: rastgele veriyle test edilen bir
 * hesap, hatayı yakaladığında bile nedenini söylemez. Doğrusal bir rampada EMA'nın ne
 * olması gerektiğini elle hesaplayabiliyoruz; gerçek piyasa verisinde hesaplayamayız.
 */
public final class TestBars {

    public static final InstrumentRef BTC =
            new InstrumentRef(1L, UUID.randomUUID(), "BINANCE", "BTCUSDT");
    public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private TestBars() {
    }

    /**
     * Doğrusal seri: kapanış her mumda {@code step} kadar değişiyor.
     *
     * <p>Hacim küçük ve <em>deterministik</em> bir salınım taşıyor. Sabit hacimli bir
     * seride varyans sıfır olur ve z-skor tanımsız kalır — gerçek piyasada olmayan,
     * yalnızca sentetik veride karşılaşılan bir durum. Testin gerçeğe benzemesi için
     * salınım var; rastgele olmaması için sabit.
     */
    public static List<Bar> ramp(int count, double start, double step) {
        List<Bar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double close = start + i * step;
            double open = close - step / 2;
            bars.add(bar(i, open, Math.max(open, close) + 0.5, Math.min(open, close) - 0.5,
                    close, volumeAt(i)));
        }
        return bars;
    }

    /** Deterministik hacim salınımı: 8–12 arası, tekrar eden ama sabit olmayan. */
    private static double volumeAt(int index) {
        return 10 + 2 * Math.sin(index * 0.9);
    }

    /** Sabit yüzdesel genlikte salınan seri — ölçek bağımsızlığı testleri için. */
    public static List<Bar> oscillating(int count, double base, double amplitudePct) {
        List<Bar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double mid = base * (1 + amplitudePct * Math.sin(i * 0.35));
            double span = base * amplitudePct;
            bars.add(bar(i, mid, mid + span, mid - span, mid, volumeAt(i)));
        }
        return bars;
    }

    /**
     * Sabit tohumlu rastgele yürüyüş — gerçekçi ama tekrarlanabilir.
     *
     * <p>Tetikleyici kapısının oranını ölçmek için gerekli: doğrusal bir rampada RSI
     * doyuma ulaşır ve hiç geçiş üretmez, dolayısıyla kapı sahte bir "%0 açılma" oranı
     * gösterir. Rastgele yürüyüş piyasanın istatistiksel dokusunu taklit ediyor.
     *
     * <p>Tohum sabit: aynı test her koşuda aynı seriyi görüyor. Rastgele tohumla ölçülen
     * bir oran, koşudan koşuya değişir ve regresyonu yakalayamaz.
     *
     * @param driftPct   mum başına ortalama yüzde sürüklenme
     * @param volPct     mum başına yüzde standart sapma
     */
    public static List<Bar> randomWalk(int count, double start, double driftPct, double volPct,
                                       long seed) {
        java.util.Random random = new java.util.Random(seed);
        List<Bar> bars = new ArrayList<>(count);
        double price = start;
        for (int i = 0; i < count; i++) {
            double open = price;
            double shock = (driftPct + volPct * random.nextGaussian()) / 100.0;
            double close = Math.max(0.01, open * (1 + shock));
            double wick = Math.abs(open) * volPct / 200.0;
            double high = Math.max(open, close) + wick;
            double low = Math.max(0.005, Math.min(open, close) - wick);
            // Hacim de oynasın: sabit hacimde z-skor tanımsız kalır.
            double volume = 10 * Math.exp(0.4 * random.nextGaussian());
            bars.add(bar(i, open, high, low, close, volume));
            price = close;
        }
        return bars;
    }

    /** Yatay seri: sabit fiyat, sıfır oynaklık. */
    public static List<Bar> flat(int count, double price) {
        List<Bar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            bars.add(bar(i, price, price, price, price, 10));
        }
        return bars;
    }

    public static Bar bar(int index, double open, double high, double low, double close,
                          double volume) {
        Instant openTime = T0.plus(Duration.ofHours(index));
        return new Bar(BTC.id(), Timeframe.H1, openTime, openTime.plus(Duration.ofHours(1)),
                BigDecimal.valueOf(open), BigDecimal.valueOf(high), BigDecimal.valueOf(low),
                BigDecimal.valueOf(close), BigDecimal.valueOf(volume),
                BigDecimal.valueOf(volume * close), 5, BigDecimal.valueOf(volume / 2), true);
    }

    public static Bar withVolume(Bar bar, double volume) {
        return new Bar(bar.instrumentId(), bar.timeframe(), bar.openTime(), bar.closeTime(),
                bar.open(), bar.high(), bar.low(), bar.close(), BigDecimal.valueOf(volume),
                bar.quoteVolume(), bar.tradeCount(), bar.takerBuyBase(), bar.isFinal());
    }

    /**
     * Sahte okuyucu.
     *
     * <p>{@code asOf} filtresi <em>gerçek</em>: mumu kapanış zamanına göre eliyor. Bu
     * filtreyi sahtelemek testi anlamsız kılardı — look-ahead korumasının çalıştığını
     * ancak filtre gerçekse doğrulayabiliriz.
     */
    public static class StubReader implements MarketDataReader {
        private final List<Bar> bars;

        public StubReader(List<Bar> bars) {
            this.bars = List.copyOf(bars);
        }

        @Override
        public List<Bar> lastFinalBars(InstrumentRef i, Timeframe tf, int count, Instant asOf) {
            List<Bar> visible = bars.stream().filter(b -> !b.closeTime().isAfter(asOf)).toList();
            return visible.size() <= count ? visible
                    : visible.subList(visible.size() - count, visible.size());
        }

        @Override
        public List<Bar> finalBars(InstrumentRef i, Timeframe tf, Instant from, Instant to) {
            return bars.stream()
                    .filter(b -> !b.openTime().isBefore(from) && b.openTime().isBefore(to))
                    .toList();
        }

        @Override
        public Optional<Bar> finalBarAt(InstrumentRef i, Timeframe tf, Instant openTime) {
            return bars.stream().filter(b -> b.openTime().equals(openTime)).findFirst();
        }

        @Override
        public Optional<Instant> lastFinalOpenTime(InstrumentRef i, Timeframe tf, Instant asOf) {
            List<Bar> visible = lastFinalBars(i, tf, 1, asOf);
            return visible.isEmpty() ? Optional.empty() : Optional.of(visible.get(0).openTime());
        }

        @Override
        public List<Gap> findGaps(InstrumentRef i, Timeframe tf, Instant from, Instant to) {
            return List.of();
        }

        @Override
        public Freshness freshness(InstrumentRef i, Timeframe tf, Instant asOf) {
            Optional<Instant> lastOpen = lastFinalOpenTime(i, tf, asOf);
            if (lastOpen.isEmpty()) {
                return new Freshness(asOf, null, Duration.ZERO, true);
            }
            Duration staleness = Duration.between(tf.closeTime(lastOpen.get()), asOf);
            return new Freshness(asOf, lastOpen.get(), staleness,
                    staleness.compareTo(tf.duration().multipliedBy(2)) > 0);
        }
    }
}
