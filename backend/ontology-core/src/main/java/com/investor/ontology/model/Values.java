package com.investor.ontology.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import com.investor.ontology.model.Value.BooleanValue;
import com.investor.ontology.model.Value.JsonValue;
import com.investor.ontology.model.Value.NumericValue;
import com.investor.ontology.model.Value.ReferenceValue;
import com.investor.ontology.model.Value.TextValue;
import com.investor.ontology.model.Value.TimestampValue;

/** {@link Value} üretmek için kısayollar. */
public final class Values {

    private Values() {
    }

    public static Value text(String v) {
        return new TextValue(v);
    }

    public static Value number(BigDecimal v) {
        return new NumericValue(v);
    }

    public static Value number(long v) {
        return new NumericValue(BigDecimal.valueOf(v));
    }

    /**
     * Ondalık değeri metinden üretir. {@code double} kabul eden bir aşırı yükleme
     * bilinçli olarak yok — kayan nokta hatası fiyat ve miktara bulaşmasın.
     */
    public static Value number(String decimal) {
        return new NumericValue(new BigDecimal(decimal));
    }

    public static Value bool(boolean v) {
        return new BooleanValue(v);
    }

    public static Value timestamp(Instant v) {
        return new TimestampValue(v);
    }

    public static Value date(LocalDate v) {
        return new TimestampValue(v.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    public static Value json(String v) {
        return new JsonValue(v);
    }

    public static Value ref(UUID objectId) {
        return new ReferenceValue(objectId);
    }

    public static Value ref(ObjectRef objectRef) {
        return new ReferenceValue(objectRef.id());
    }
}
