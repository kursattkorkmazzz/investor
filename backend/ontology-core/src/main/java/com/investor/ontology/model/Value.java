package com.investor.ontology.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Bir alanın tipli değeri.
 *
 * <p>Kapalı (sealed) bir hiyerarşi: yeni bir değer tipi eklemek, onu işleyen her yeri
 * derleme hatasıyla işaretler. Para ve miktar taşıyan her şey {@link BigDecimal} —
 * {@code double} bilinçli olarak yok.
 */
public sealed interface Value {

    record TextValue(String value) implements Value {
        public TextValue {
            if (value == null) {
                throw new IllegalArgumentException("metin değeri null olamaz");
            }
        }
    }

    record NumericValue(BigDecimal value) implements Value {
        public NumericValue {
            if (value == null) {
                throw new IllegalArgumentException("sayısal değer null olamaz");
            }
        }
    }

    record BooleanValue(boolean value) implements Value {
    }

    record TimestampValue(Instant value) implements Value {
        public TimestampValue {
            if (value == null) {
                throw new IllegalArgumentException("zaman değeri null olamaz");
            }
        }
    }

    /** Ham JSON metni. Doğrulama {@code property_type.constraints} üzerinden yapılır. */
    record JsonValue(String json) implements Value {
        public JsonValue {
            if (json == null) {
                throw new IllegalArgumentException("json değeri null olamaz");
            }
        }
    }

    record ReferenceValue(UUID objectId) implements Value {
        public ReferenceValue {
            if (objectId == null) {
                throw new IllegalArgumentException("referans değeri null olamaz");
            }
        }
    }
}
