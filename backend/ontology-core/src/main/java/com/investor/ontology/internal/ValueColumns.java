package com.investor.ontology.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code property_value} tablosundaki altı tipli değer kolonunun tek seferlik taşıyıcısı.
 * Tam olarak biri dolu olur — bu, veritabanındaki {@code pv_exactly_one_value} kısıtının
 * Java tarafındaki karşılığıdır.
 */
record ValueColumns(String text, BigDecimal numeric, Boolean bool, Instant timestamp, String json, UUID ref) {

    static ValueColumns ofText(String v) {
        return new ValueColumns(v, null, null, null, null, null);
    }

    static ValueColumns ofNumeric(BigDecimal v) {
        return new ValueColumns(null, v, null, null, null, null);
    }

    static ValueColumns ofBool(boolean v) {
        return new ValueColumns(null, null, v, null, null, null);
    }

    static ValueColumns ofTimestamp(Instant v) {
        return new ValueColumns(null, null, null, v, null, null);
    }

    static ValueColumns ofJson(String v) {
        return new ValueColumns(null, null, null, null, v, null);
    }

    static ValueColumns ofRef(UUID v) {
        return new ValueColumns(null, null, null, null, null, v);
    }
}
