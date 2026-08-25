package com.investor.knowledge.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Enstrümana bağlı olmayan ingest imleçleri: FRED serileri, takvim, on-chain kaynaklar. */
class IngestCursors {

    private final JdbcClient jdbc;

    IngestCursors(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<String> cursor(String sourceKey) {
        return jdbc.sql("SELECT cursor_value FROM ingest_cursor WHERE source_key = :key")
                .param("key", sourceKey)
                .query(String.class)
                .optional();
    }

    void recordSuccess(String sourceKey, String cursorValue, Instant at) {
        jdbc.sql("""
                INSERT INTO ingest_cursor (source_key, cursor_value, last_attempt_at,
                                           last_success_at, consecutive_errors, last_error)
                VALUES (:key, :cursor, :at, :at, 0, NULL)
                ON CONFLICT (source_key) DO UPDATE SET
                    cursor_value       = EXCLUDED.cursor_value,
                    last_attempt_at    = EXCLUDED.last_attempt_at,
                    last_success_at    = EXCLUDED.last_success_at,
                    consecutive_errors = 0,
                    last_error         = NULL
                """)
                .param("key", sourceKey).param("cursor", cursorValue)
                .param("at", Timestamp.from(at))
                .update();
    }

    void recordFailure(String sourceKey, Instant at, String error) {
        jdbc.sql("""
                INSERT INTO ingest_cursor (source_key, last_attempt_at, consecutive_errors, last_error)
                VALUES (:key, :at, 1, :error)
                ON CONFLICT (source_key) DO UPDATE SET
                    last_attempt_at    = EXCLUDED.last_attempt_at,
                    consecutive_errors = ingest_cursor.consecutive_errors + 1,
                    last_error         = EXCLUDED.last_error
                """)
                .param("key", sourceKey).param("at", Timestamp.from(at))
                .param("error", error == null ? null : error.substring(0, Math.min(error.length(), 500)))
                .update();
    }
}
