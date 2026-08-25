package com.investor.marketdata.internal;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.investor.marketdata.MarketDataReader;
import com.investor.marketdata.model.Bar;
import com.investor.marketdata.model.Freshness;
import com.investor.marketdata.model.Gap;
import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.Timeframe;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link MarketDataReader}'ın JDBC gerçeklemesi.
 *
 * <p>Her sorgu {@code is_final} filtresini taşır. Bu filtre tek bir yerde unutulursa
 * backtest sessizce geleceği görmeye başlar; bu yüzden okuma yolları burada toplanmıştır
 * ve modül dışına kapanmamış mum döndüren bir metot açılmaz.
 */
class JdbcMarketDataReader implements MarketDataReader {

    private static final String COLUMNS = """
            instrument_id, timeframe, open_time, close_time,
            open, high, low, close, volume, quote_volume, trade_count, taker_buy_base, is_final
            """;

    private final JdbcClient jdbc;
    private final Duration stalenessTolerance;

    JdbcMarketDataReader(JdbcClient jdbc, Duration stalenessTolerance) {
        this.jdbc = jdbc;
        this.stalenessTolerance = stalenessTolerance;
    }

    @Override
    public List<Bar> finalBars(InstrumentRef instrument, Timeframe timeframe,
                               Instant fromInclusive, Instant toExclusive) {
        requireRange(fromInclusive, toExclusive);
        return jdbc.sql("""
                SELECT %s FROM ohlcv
                 WHERE instrument_id = :id AND timeframe = :tf AND is_final
                   AND open_time >= :from AND open_time < :to
                 ORDER BY open_time
                """.formatted(COLUMNS))
                .param("id", instrument.id())
                .param("tf", timeframe.code())
                .param("from", ts(fromInclusive))
                .param("to", ts(toExclusive))
                .query(BarRowMapper.INSTANCE)
                .list();
    }

    @Override
    public List<Bar> lastFinalBars(InstrumentRef instrument, Timeframe timeframe, int count, Instant asOf) {
        if (count <= 0) {
            throw new IllegalArgumentException("mum sayısı pozitif olmalı");
        }
        // close_time < asOf: asOf anında hâlâ açık olan mum dışarıda kalır.
        List<Bar> descending = jdbc.sql("""
                SELECT %s FROM ohlcv
                 WHERE instrument_id = :id AND timeframe = :tf AND is_final
                   AND close_time < :asOf
                 ORDER BY open_time DESC
                 LIMIT :count
                """.formatted(COLUMNS))
                .param("id", instrument.id())
                .param("tf", timeframe.code())
                .param("asOf", ts(asOf))
                .param("count", count)
                .query(BarRowMapper.INSTANCE)
                .list();

        List<Bar> ascending = new ArrayList<>(descending);
        java.util.Collections.reverse(ascending);
        return List.copyOf(ascending);
    }

    @Override
    public Optional<Bar> finalBarAt(InstrumentRef instrument, Timeframe timeframe, Instant openTime) {
        return jdbc.sql("""
                SELECT %s FROM ohlcv
                 WHERE instrument_id = :id AND timeframe = :tf AND open_time = :openTime AND is_final
                """.formatted(COLUMNS))
                .param("id", instrument.id())
                .param("tf", timeframe.code())
                .param("openTime", ts(openTime))
                .query(BarRowMapper.INSTANCE)
                .optional();
    }

    @Override
    public Optional<Instant> lastFinalOpenTime(InstrumentRef instrument, Timeframe timeframe, Instant asOf) {
        return jdbc.sql("""
                SELECT max(open_time) FROM ohlcv
                 WHERE instrument_id = :id AND timeframe = :tf AND is_final AND close_time < :asOf
                """)
                .param("id", instrument.id())
                .param("tf", timeframe.code())
                .param("asOf", ts(asOf))
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    /**
     * Eksik mumları bulur ve ardışık olanları tek bir boşluğa toplar.
     *
     * <p>Beklenen açılış zamanları {@code generate_series} ile üretilir; veritabanında
     * karşılığı olmayanlar boşluktur. Aralık, zaman dilimine hizalanır — hizalanmamış
     * bir başlangıç, her mumu eksik gösterirdi.
     */
    @Override
    public List<Gap> findGaps(InstrumentRef instrument, Timeframe timeframe,
                              Instant fromInclusive, Instant toExclusive) {
        requireRange(fromInclusive, toExclusive);
        Instant from = timeframe.floor(fromInclusive);
        Instant to = timeframe.floor(toExclusive);
        if (!to.isAfter(from)) {
            return List.of();
        }

        List<Instant> missing = jdbc.sql("""
                SELECT expected.open_time
                  FROM generate_series(:from, :lastOpen, make_interval(secs => :step)) AS expected(open_time)
                  LEFT JOIN ohlcv o
                         ON o.open_time = expected.open_time
                        AND o.instrument_id = :id
                        AND o.timeframe = :tf
                        AND o.is_final
                 WHERE o.open_time IS NULL
                 ORDER BY expected.open_time
                """)
                .param("from", ts(from))
                .param("lastOpen", ts(to.minus(timeframe.duration())))
                .param("step", (double) timeframe.duration().toSeconds())
                .param("id", instrument.id())
                .param("tf", timeframe.code())
                .query(OffsetDateTime.class)
                .list()
                .stream()
                .map(OffsetDateTime::toInstant)
                .toList();

        return groupConsecutive(missing, timeframe);
    }

    @Override
    public Freshness freshness(InstrumentRef instrument, Timeframe timeframe, Instant asOf) {
        Optional<Instant> last = lastFinalOpenTime(instrument, timeframe, asOf);
        if (last.isEmpty()) {
            return Freshness.missing(asOf);
        }
        // Beklenen en son kapanmış muma göre gecikme; mumun kendi süresi gecikme sayılmaz.
        Instant expected = timeframe.lastClosedOpen(asOf);
        Duration staleness = Duration.between(last.get(), expected);
        if (staleness.isNegative()) {
            staleness = Duration.ZERO;
        }
        return new Freshness(asOf, last.get(), staleness, staleness.compareTo(stalenessTolerance) > 0);
    }

    private static List<Gap> groupConsecutive(List<Instant> missing, Timeframe timeframe) {
        if (missing.isEmpty()) {
            return List.of();
        }
        List<Gap> gaps = new ArrayList<>();
        Instant runStart = missing.get(0);
        Instant previous = runStart;
        long count = 1;

        for (int i = 1; i < missing.size(); i++) {
            Instant current = missing.get(i);
            if (current.equals(previous.plus(timeframe.duration()))) {
                count++;
            } else {
                gaps.add(new Gap(timeframe, runStart, previous.plus(timeframe.duration()), count));
                runStart = current;
                count = 1;
            }
            previous = current;
        }
        gaps.add(new Gap(timeframe, runStart, previous.plus(timeframe.duration()), count));
        return List.copyOf(gaps);
    }

    private static void requireRange(Instant from, Instant to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("zaman aralığı zorunlu");
        }
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("aralık sonu başlangıçtan sonra olmalı: %s → %s".formatted(from, to));
        }
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
