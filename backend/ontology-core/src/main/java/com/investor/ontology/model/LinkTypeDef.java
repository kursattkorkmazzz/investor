package com.investor.ontology.model;

import java.util.Map;
import java.util.UUID;

public record LinkTypeDef(
        UUID id,
        String apiName,
        String displayName,
        String reverseApiName,
        String reverseDisplayName,
        String fromTypeApiName,
        String toTypeApiName,
        LinkCardinality cardinality,
        boolean isSymmetric,
        Map<String, Object> propertySchema) {
}
