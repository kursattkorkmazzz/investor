package com.investor.ontology.model;

import java.util.Map;

/** Yeni bir ilişki tipi tanımlama komutu. */
public record NewLinkType(
        String apiName,
        String displayName,
        String reverseApiName,
        String reverseDisplayName,
        String fromTypeApiName,
        String toTypeApiName,
        LinkCardinality cardinality,
        boolean isSymmetric,
        Map<String, Object> propertySchema) {

    public static NewLinkType of(String apiName, String displayName,
                                 String reverseApiName, String reverseDisplayName,
                                 String fromType, String toType, LinkCardinality cardinality) {
        return new NewLinkType(apiName, displayName, reverseApiName, reverseDisplayName,
                fromType, toType, cardinality, false, Map.of());
    }
}
