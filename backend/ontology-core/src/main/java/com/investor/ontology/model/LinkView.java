package com.investor.ontology.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Okuma tarafında bir ilişki. */
public record LinkView(
        String linkApiName,
        UUID targetObjectId,
        String targetTypeApiName,
        String targetExternalId,
        String targetTitle,
        BigDecimal weight,
        Map<String, Object> properties,
        Instant validFrom,
        Instant validTo) {
}
