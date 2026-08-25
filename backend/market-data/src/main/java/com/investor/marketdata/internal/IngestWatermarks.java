package com.investor.marketdata.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.Timeframe;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Ingest ilerleme işaretleri.
 *
 * <p>Backfill'in kaldığı yerden devam edebilmesi için. Hata sayacı da burada: bir kaynak
 * üst üste başarısız oluyorsa bu, veri tazeliği kontrolünün göreceği bir sinyaldir.
 */
class IngestWatermarks {

    private final JdbcClient jdbc;

    IngestWatermarks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Instant> lastFinalOpen(InstrumentRef instrument, Timeframe timeframe) {
        return jdbc.sql("""
                SELECT last_final_open FROM ingest_watermark
                 WHERE instrument_id = :id AND timeframe = :tf
                """)
                .param("id", instrument.id())
                .param("tf", timeframe.code())
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    void recordSuccess(InstrumentRef instrument, Timeframe timeframe, Instant lastFinalOpen, Instant at) {
        jdbc.sql("""
                INSERT INTO ingest_watermark (instrument_id, timeframe, last_final_open,
                                              last_attempt_at, last_success_at, consecutive_errors, last_error)
                VALUES (:id, :tf, :lastOpen, :at, :at, 0, NULL)
                ON CONFLICT (instrument_id, timeframe) DO UPDATE SET
                    -- İşaret geriye gitmez: eski bir aralığın backfill'i ilerlemeyi geri almasın.
                    last_final_open    = GREATEST(ingest_watermark.last_final_open, EXCLUDED.last_final_open),
                    last_attempt_at    = EXCLUDED.last_attempt_at,
                    last_success_at    = EXCLUDED.last_success_at,
                    consecutive_errors = 0,
                    last_error         = NULL
                """)
                .param("id", instrument.id())
                .param("tf", timeframe.code())
                .param("lastOpen", lastFinalOpen == null ? null : Timestamp.from(lastFinalOpen))
                .param("at", Timestamp.from(at))
                .update();
    }

    void recordFailure(InstrumentRef instrument, Timeframe timeframe, Instant at, String error) {
        jdbc.sql("""
                INSERT INTO ingest_watermark (instrument_id, timeframe, last_attempt_at,
                                              consecutive_errors, last_error)
                VALUES (:id, :tf, :at, 1, :error)
                ON CONFLICT (instrument_id, timeframe) DO UPDATE SET
                    last_attempt_at    = EXCLUDED.last_attempt_at,
                    consecutive_errors = ingest_watermark.consecutive_errors + 1,
                    last_error         = EXCLUDED.last_error
                """)
                .param("id", instrument.id())
                .param("tf", timeframe.code())
                .param("at", Timestamp.from(at))
                .param("error", error == null ? null : error.substring(0, Math.min(error.length(), 500)))
                .update();
    }
}
