package com.investor.ontology;

/** Ontoloji katmanının kök hata tipi. */
public class OntologyException extends RuntimeException {

    public OntologyException(String message) {
        super(message);
    }

    public OntologyException(String message, Throwable cause) {
        super(message, cause);
    }

    /** İstenen tip, alan, ilişki veya nesne bulunamadı. */
    public static class NotFound extends OntologyException {
        public NotFound(String message) {
            super(message);
        }
    }

    /** Şema kısıtı ihlal edildi (tip uyuşmazlığı, zorunlu alan, constraints). */
    public static class SchemaViolation extends OntologyException {
        public SchemaViolation(String message) {
            super(message);
        }
    }

    /**
     * Yazılmak istenen geçerlilik aralığı, aynı alan için mevcut bir aralıkla çakışıyor.
     *
     * <p>Veritabanındaki {@code EXCLUDE} kısıtından doğar. Uygulama hatasının sessizce
     * ikili gerçek üretmesi yerine burada durur.
     */
    public static class TemporalConflict extends OntologyException {
        public TemporalConflict(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Aynı tip + external_id ile ikinci bir nesne oluşturulmaya çalışıldı. */
    public static class DuplicateObject extends OntologyException {
        public DuplicateObject(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
