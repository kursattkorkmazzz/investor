package com.investor.analysis.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Tek bir istatistik değeri.
 *
 * <p>{@link IndicatorValue} ile aynı disiplin: değerin yanında <b>nasıl hesaplandığı</b>
 * ve <b>kaç gözleme dayandığı</b> taşınıyor. "90. persentil" ifadesi 30 gözleme
 * dayanıyorsa ile 2000 gözleme dayanıyorsa aynı şeyi ifade etmez; modele bu farkı
 * göstermeden sayıyı vermek, ona olmayan bir kesinlik satmaktır.
 *
 * @param sampleSize hesaba giren gözlem sayısı
 * @param method     insan (ve model) okuyabilir hesap tarifi; istemin içine giriyor
 */
public record StatValue(String name, BigDecimal value, int sampleSize, String method) {

    public StatValue {
        Objects.requireNonNull(name, "istatistik adı zorunlu");
        Objects.requireNonNull(value, "istatistik değeri zorunlu");
        if (sampleSize <= 0) {
            throw new IllegalArgumentException("gözlem sayısı pozitif olmalı: " + name);
        }
    }

    public double doubleValue() {
        return value.doubleValue();
    }
}
