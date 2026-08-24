package com.investor.ontology.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.investor.ontology.OntologyException;

import org.springframework.dao.DataIntegrityViolationException;

/** JDBC ile java.time ve PostgreSQL hata kodları arasındaki köşe durumları. */
final class SqlSupport {

    /** PostgreSQL: exclusion_violation — geçerlilik aralıkları çakıştı. */
    static final String SQLSTATE_EXCLUSION_VIOLATION = "23P01";
    /** PostgreSQL: unique_violation. */
    static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    private SqlSupport() {
    }

    /**
     * pgjdbc {@code java.time.Instant}'ı doğrudan bağlamaz; UTC {@link OffsetDateTime}'a çeviriyoruz.
     */
    static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    static UUID uuid(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        return raw == null ? null : UUID.fromString(raw);
    }

    static String sqlState(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            cause = cause.getCause();
        }
        return null;
    }

    /**
     * Veritabanı kısıtlarını anlamlı ontoloji hatalarına çevirir.
     *
     * <p>{@code EXCLUDE} kısıtı, uygulama hatasının sessizce ikili gerçek üretmesini
     * engelleyen son savunma hattı — buraya düşmesi bir hatanın yakalandığı anlamına gelir.
     */
    static RuntimeException translate(DataIntegrityViolationException e, String context) {
        String state = sqlState(e);
        if (SQLSTATE_EXCLUSION_VIOLATION.equals(state)) {
            return new OntologyException.TemporalConflict(
                    context + ": yazılmak istenen geçerlilik aralığı mevcut bir aralıkla çakışıyor", e);
        }
        if (SQLSTATE_UNIQUE_VIOLATION.equals(state)) {
            return new OntologyException.DuplicateObject(context + ": kayıt zaten var", e);
        }
        return e;
    }
}
