package com.investor.knowledge.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Tekilleştirilmiş haber kümesi.
 *
 * @param sourceCount kümeye katkı veren kaynak sayısı. Kanıt ağırlığını etkiler ama
 *                    kanıt sayısını çoğaltmaz — haberin önemi, kaç yerde yayınlandığıyla
 *                    aynı şey değil.
 */
public record NewsClusterView(
        UUID id,
        UUID objectId,
        String title,
        int sourceCount,
        Instant firstPublishedAt,
        Instant lastPublishedAt) {
}
