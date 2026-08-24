package com.investor.ontology.query;

/**
 * Sorgu DSL'inde izin verilen operatörler.
 *
 * <p>Beyaz liste bilinçli: derleyici yalnızca bu enum'daki operatörler için SQL üretir,
 * gelen metinden operatör türetmez.
 */
public enum Operator {
    EQ(true),
    NEQ(true),
    GT(true),
    GTE(true),
    LT(true),
    LTE(true),
    IN(true),
    CONTAINS(true),
    STARTS_WITH(true),
    IS_NULL(false),
    IS_NOT_NULL(false);

    private final boolean needsValue;

    Operator(boolean needsValue) {
        this.needsValue = needsValue;
    }

    public boolean needsValue() {
        return needsValue;
    }
}
