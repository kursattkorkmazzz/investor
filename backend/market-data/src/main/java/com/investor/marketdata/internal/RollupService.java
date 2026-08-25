package com.investor.marketdata.internal;

import java.sql.Timestamp;
import java.time.Instant;

import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.Timeframe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Taban (1m) mumlardan üst zaman dilimlerini türetir.
 *
 * <h2>Neden bunu kendimiz yazıyoruz</h2>
 * TimescaleDB'nin continuous aggregate'i bu işi yapardı ama RDS onu desteklemiyor
 * ({@code docs/adr/0003}). Karşılığında rollup'ın doğruluğundan biz sorumluyuz.
 *
 * <h2>Kritik kısıt: eksik veriden mum üretilmez</h2>
 * {@code HAVING count(*) = :expectedBars} satırı bu sınıfın en önemli parçası. Eksik bir
 * 1m mumundan üretilmiş 15m mum, hatalı olduğu <em>hiçbir yerde görünmeyen</em> bir
 * indikatör üretir: değerler makul görünür, grafik normal çizilir, karar yanlış verilir.
 * Yarım veriden mum yazmaktansa hiç yazmamak ve boşluğu doldurup tekrar denemek doğru.
 *
 * <p>Yalnızca {@code is_final} taban mumlar kaynak alınır; kapanmamış bir 1m mum
 * rollup'a girerse üst mum kapanmadan değişmeye devam eder.
 */
class RollupService {

    private static final Logger log = LoggerFactory.getLogger(RollupService.class);

    private static final String ROLLUP = """
            INSERT INTO ohlcv (instrument_id, timeframe, open_time, close_time,
                               open, high, low, close,
                               volume, quote_volume, trade_count, taker_buy_base, is_final)
            SELECT
                src.instrument_id,
                :targetTf,
                :bucketStart,
                :bucketEnd,
                (array_agg(src.open  ORDER BY src.open_time ASC ))[1],
                max(src.high),
                min(src.low),
                (array_agg(src.close ORDER BY src.open_time DESC))[1],
                sum(src.volume),
                sum(src.quote_volume),
                sum(src.trade_count),
                sum(src.taker_buy_base),
                true
              FROM ohlcv src
             WHERE src.instrument_id = :instrumentId
               AND src.timeframe = :sourceTf
               AND src.open_time >= :bucketStart
               AND src.open_time <  :bucketEndOpen
               AND src.is_final
             GROUP BY src.instrument_id
            HAVING count(*) = :expectedBars
            ON CONFLICT (instrument_id, timeframe, open_time) DO UPDATE SET
                close_time     = EXCLUDED.close_time,
                open           = EXCLUDED.open,
                high           = EXCLUDED.high,
                low            = EXCLUDED.low,
                close          = EXCLUDED.close,
                volume         = EXCLUDED.volume,
                quote_volume   = EXCLUDED.quote_volume,
                trade_count    = EXCLUDED.trade_count,
                taker_buy_base = EXCLUDED.taker_buy_base,
                is_final       = true,
                ingested_at    = now()
            """;

    private final JdbcClient jdbc;
    private final BarWriter writer;

    RollupService(JdbcClient jdbc, BarWriter writer) {
        this.jdbc = jdbc;
        this.writer = writer;
    }

    /**
     * Aralıktaki tüm hedef mumları üretir.
     *
     * @param toExclusive üst sınır; yalnızca tamamen bu sınırın altında kalan mumlar üretilir
     * @return yazılan mum sayısı
     */
    int rollup(InstrumentRef instrument, Timeframe target, Instant fromInclusive, Instant toExclusive) {
        if (target == Timeframe.BASE) {
            throw new IllegalArgumentException("taban zaman dilimi türetilemez: " + target.code());
        }
        Instant cursor = target.floor(fromInclusive);
        int expectedBars = target.baseBarCount();
        int written = 0;
        int skipped = 0;

        while (!cursor.plus(target.duration()).isAfter(toExclusive)) {
            Instant bucketEndOpen = cursor.plus(target.duration());
            writer.ensureMonth(cursor);

            int rows = jdbc.sql(ROLLUP)
                    .param("targetTf", target.code())
                    .param("sourceTf", Timeframe.BASE.code())
                    .param("instrumentId", instrument.id())
                    .param("bucketStart", Timestamp.from(cursor))
                    .param("bucketEnd", Timestamp.from(target.closeTime(cursor)))
                    .param("bucketEndOpen", Timestamp.from(bucketEndOpen))
                    .param("expectedBars", expectedBars)
                    .update();

            if (rows > 0) {
                written += rows;
            } else {
                skipped++;
            }
            cursor = bucketEndOpen;
        }

        if (skipped > 0) {
            log.debug("{} {} rollup: {} mum yazıldı, {} kova eksik taban veri yüzünden atlandı",
                    instrument.qualifiedSymbol(), target.code(), written, skipped);
        }
        return written;
    }
}
