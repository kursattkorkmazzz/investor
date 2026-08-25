package com.investor.analysis.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Tek bir hesaplanmış gösterge değeri.
 *
 * <p>Değerin yanında <b>nasıl hesaplandığı</b> da taşınıyor ve bu tesadüf değil. LLM'e
 * çıplak bir "rsi14: 28.4" verilseydi, model bu sayının hangi zaman diliminden, kaç
 * mumdan, hangi yumuşatmayla geldiğini varsaymak zorunda kalırdı — ve varsayardı.
 * {@code method} alanı istemin içine olduğu gibi giriyor.
 *
 * @param barsUsed hesaba giren mum sayısı. Isınma yetersizse gösterge hiç üretilmiyor
 *                 (bkz. {@link IndicatorSet#unavailable()}), ama üretilenlerde de bu
 *                 sayı görünür kalıyor: 200 mumdan hesaplanan EMA200 ile 900 mumdan
 *                 hesaplanan EMA200 aynı güvenilirlikte değil.
 * @param method   insan (ve model) okuyabilir hesap tarifi
 */
public record IndicatorValue(
        String name,
        BigDecimal value,
        int period,
        int barsUsed,
        String method) {

    public IndicatorValue {
        Objects.requireNonNull(name, "gösterge adı zorunlu");
        Objects.requireNonNull(value, "gösterge değeri zorunlu");
        if (barsUsed <= 0) {
            throw new IllegalArgumentException("kullanılan mum sayısı pozitif olmalı: " + name);
        }
    }

    public double doubleValue() {
        return value.doubleValue();
    }
}
