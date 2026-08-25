package com.investor.analysis.model;

import java.util.Objects;

/**
 * Pahalı analiz turunu açan tek bir sebep.
 *
 * <p>Sebep <em>kaydediliyor</em>, çünkü sonradan sorulacak soru şu: "RSI eşiğiyle açılan
 * turlar gerçekten iyi kararlar üretti mi, yoksa sadece para mı harcadı?" Tur sayısı
 * bilinip sebebi bilinmeseydi bu soru cevaplanamazdı ve tetikleyici listesi hiç
 * budanamazdı — her bileşen kendi faydasını kanıtlamak zorunda.
 *
 * @param magnitude sebebin şiddeti (eşiği ne kadar aştığı). Karşılaştırılabilir bir sayı
 *                  değil, aynı tür içinde sıralama içindir.
 */
public record Trigger(Type type, String detail, double magnitude) {

    public Trigger {
        Objects.requireNonNull(type, "tetikleyici türü zorunlu");
        detail = detail == null ? "" : detail;
    }

    public enum Type {
        /** RSI aşırı alım/satım bölgesine <em>girdi</em> — içinde kalmak tetiklemez. */
        RSI_EXTREME,
        /** MACD histogramı işaret değiştirdi. */
        MACD_CROSS,
        /** Fiyat Bollinger bantlarının dışına çıktı. */
        BOLLINGER_BREAKOUT,
        /** Hacim kendi geçmişine göre olağandışı. */
        VOLUME_ANOMALY,
        /** Son mumun hareketi ATR'nin katını aştı. */
        PRICE_SHOCK,
        /** Önemli yeni haber geldi. */
        MATERIAL_NEWS,
        /** Rejim sınıflandırması değişti. */
        REGIME_CHANGE,
        /** Açık pozisyon için planlı gözden geçirme. */
        SCHEDULED_REVIEW
    }

    public static Trigger of(Type type, String detail, double magnitude) {
        return new Trigger(type, detail, magnitude);
    }
}
