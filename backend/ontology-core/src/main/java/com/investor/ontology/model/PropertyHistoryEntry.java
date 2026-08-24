package com.investor.ontology.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Bir alanın geçmişindeki tek kayıt.
 *
 * <p>Ontology Explorer'daki zaman çizelgesi bunları listeler. Geri çekilmiş kayıtlar
 * gizlenmez — sistemin bir dönem yanlış bilgiyle çalıştığı görünür olmalıdır.
 *
 * @param validFrom   gerçek dünyada ne zaman doğru olmaya başladı
 * @param validTo     ne zamana kadar doğruydu ({@code null} = hâlâ geçerli)
 * @param recordedAt  biz ne zaman öğrendik
 * @param retractedAt bu kaydın yanlış olduğunu ne zaman anladık ({@code null} = geçerli kayıt)
 */
public record PropertyHistoryEntry(
        long valueId,
        String propertyApiName,
        int ordinal,
        Object value,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt,
        Instant retractedAt,
        UUID commitId,
        ActorType actorType,
        String actorId,
        String reason,
        String sourceName,
        BigDecimal confidence) {

    public boolean isRetracted() {
        return retractedAt != null;
    }

    public boolean isOpen() {
        return validTo == null && retractedAt == null;
    }
}
