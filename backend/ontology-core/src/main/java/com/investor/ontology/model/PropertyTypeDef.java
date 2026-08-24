package com.investor.ontology.model;

import java.util.Map;
import java.util.UUID;

/**
 * Bir alanın tanımı.
 *
 * @param constraints          min/max/minLength/maxLength/pattern/enumValues
 * @param deprecatedInVersion  null değilse alan artık kullanılmamalı; veri durur, UI gizler
 */
public record PropertyTypeDef(
        UUID id,
        UUID objectTypeId,
        String apiName,
        String displayName,
        String description,
        DataType dataType,
        Cardinality cardinality,
        boolean isRequired,
        boolean isTitle,
        String unit,
        Map<String, Object> constraints,
        Integer introducedInVersion,
        Integer deprecatedInVersion,
        int displayOrder) {

    public boolean isDeprecated() {
        return deprecatedInVersion != null;
    }
}
