package com.investor.analysis.model;

import java.util.Objects;

/**
 * Piyasa rejimi: trend yönü ve oynaklık seviyesi.
 *
 * <p>İki eksen ayrı tutuluyor çünkü farklı şeyler söylüyorlar ve farklı davranış
 * gerektiriyorlar. "Yükseliyor" ile "sakin yükseliyor" aynı pozisyon boyutunu hak etmez;
 * yüksek oynaklıkta aynı stop mesafesi çok daha sık tetiklenir.
 *
 * @param trendStrength EMA'lar arasındaki göreli ayrışma, %. Sınıflandırmanın kendisi
 *                      kategorik ama gücü sürekli; LLM'e "zayıf yükseliş" ile "güçlü
 *                      yükseliş" arasındaki farkı gösteren şey bu sayı.
 * @param rationale     sınıflandırmanın gerekçesi — istemin içine giriyor. Modele çıplak
 *                      bir etiket verilseydi neye dayandığını varsaymak zorunda kalırdı.
 */
public record Regime(Trend trend, Volatility volatility, double trendStrength, String rationale) {

    public Regime {
        Objects.requireNonNull(trend, "trend zorunlu");
        Objects.requireNonNull(volatility, "oynaklık zorunlu");
        rationale = rationale == null ? "" : rationale;
    }

    /**
     * Trend yönü.
     *
     * <p>{@link #UNKNOWN} gerçek bir durum, tembellik değil: EMA200 ısınmamışsa trend
     * <em>bilinmiyor</em>dur. Bunu {@link #RANGE} saymak, "yatay seyrediyor" diye bir
     * iddiada bulunmak olurdu — oysa elimizde iddia edecek veri yok.
     */
    public enum Trend {
        UPTREND,
        DOWNTREND,
        RANGE,
        UNKNOWN
    }

    /** Oynaklık seviyesi, kendi tarihsel dağılımına göre. */
    public enum Volatility {
        LOW,
        NORMAL,
        HIGH,
        UNKNOWN
    }

    public static Regime unknown(String reason) {
        return new Regime(Trend.UNKNOWN, Volatility.UNKNOWN, 0, reason);
    }

    /** Sınıflandırma yapılabildi mi — ajanların çekimser kalma kararında kullanılıyor. */
    public boolean isKnown() {
        return trend != Trend.UNKNOWN && volatility != Volatility.UNKNOWN;
    }

    /**
     * Kategorik olarak farklı mı.
     *
     * <p>{@code trendStrength} kasten karşılaştırmaya girmiyor: sürekli bir sayının her
     * kıpırdanışı "rejim değişti" sayılsaydı tetikleyici kapısı her turda açılır ve
     * maliyet tasarımının tamamı çökerdi.
     */
    public boolean differsFrom(Regime other) {
        return other == null || trend != other.trend || volatility != other.volatility;
    }

    /** Kısa etiket: {@code UPTREND/HIGH}. */
    public String label() {
        return trend + "/" + volatility;
    }
}
