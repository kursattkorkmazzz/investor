package com.investor.ontology.model;

/**
 * Bir alanın veri tipi. Fiziksel olarak {@code property_value} tablosundaki tipli
 * kolonlardan birine eşlenir — JSONB'ye değil, çünkü alan bazlı indeksleme ve
 * karşılaştırma gerekiyor.
 */
public enum DataType {

    STRING("value_text"),
    TEXT("value_text"),
    ENUM("value_text"),
    INTEGER("value_numeric"),
    DECIMAL("value_numeric"),
    BOOLEAN("value_bool"),
    TIMESTAMP("value_ts"),
    DATE("value_ts"),
    JSON("value_json"),
    REFERENCE("value_ref");

    private final String column;

    DataType(String column) {
        this.column = column;
    }

    /** Değerin saklandığı fiziksel kolon adı. */
    public String column() {
        return column;
    }

    public boolean isNumeric() {
        return this == INTEGER || this == DECIMAL;
    }

    public boolean isTextual() {
        return this == STRING || this == TEXT || this == ENUM;
    }
}
