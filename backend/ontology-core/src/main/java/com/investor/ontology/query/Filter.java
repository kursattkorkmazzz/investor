package com.investor.ontology.query;

import java.util.List;

/**
 * Tek bir filtre koşulu.
 *
 * @param field alan API adı — {@code property_type}'a karşı doğrulanır, doğrudan SQL'e girmez
 * @param value {@link Operator#IN} için {@link List}, diğerleri için skaler
 */
public record Filter(String field, Operator op, Object value) {

    public Filter {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("filtre alanı zorunlu");
        }
        if (op == null) {
            throw new IllegalArgumentException("filtre operatörü zorunlu");
        }
        if (op.needsValue() && value == null) {
            throw new IllegalArgumentException(op + " operatörü değer gerektirir");
        }
        if (op == Operator.IN && !(value instanceof List<?> list && !list.isEmpty())) {
            throw new IllegalArgumentException("IN operatörü boş olmayan bir liste bekler");
        }
    }

    public static Filter eq(String field, Object value) {
        return new Filter(field, Operator.EQ, value);
    }

    public static Filter gt(String field, Object value) {
        return new Filter(field, Operator.GT, value);
    }

    public static Filter gte(String field, Object value) {
        return new Filter(field, Operator.GTE, value);
    }

    public static Filter lt(String field, Object value) {
        return new Filter(field, Operator.LT, value);
    }

    public static Filter in(String field, List<?> values) {
        return new Filter(field, Operator.IN, values);
    }

    public static Filter contains(String field, String needle) {
        return new Filter(field, Operator.CONTAINS, needle);
    }

    public static Filter isNull(String field) {
        return new Filter(field, Operator.IS_NULL, null);
    }

    public static Filter isNotNull(String field) {
        return new Filter(field, Operator.IS_NOT_NULL, null);
    }
}
