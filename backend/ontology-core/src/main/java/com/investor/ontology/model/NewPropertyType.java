package com.investor.ontology.model;

import java.util.Map;

/** Bir tipe yeni alan ekleme komutu. */
public record NewPropertyType(
        String apiName,
        String displayName,
        String description,
        DataType dataType,
        Cardinality cardinality,
        boolean isRequired,
        boolean isTitle,
        String unit,
        Map<String, Object> constraints,
        int displayOrder) {

    public static NewPropertyType of(String apiName, String displayName, DataType dataType) {
        return new NewPropertyType(apiName, displayName, null, dataType,
                Cardinality.SINGLE, false, false, null, Map.of(), 0);
    }

    public NewPropertyType asTitle() {
        return new NewPropertyType(apiName, displayName, description, dataType,
                cardinality, isRequired, true, unit, constraints, displayOrder);
    }

    public NewPropertyType asList() {
        return new NewPropertyType(apiName, displayName, description, dataType,
                Cardinality.LIST, isRequired, isTitle, unit, constraints, displayOrder);
    }

    public NewPropertyType required() {
        return new NewPropertyType(apiName, displayName, description, dataType,
                cardinality, true, isTitle, unit, constraints, displayOrder);
    }

    public NewPropertyType withConstraints(Map<String, Object> c) {
        return new NewPropertyType(apiName, displayName, description, dataType,
                cardinality, isRequired, isTitle, unit, c, displayOrder);
    }

    public NewPropertyType withUnit(String u) {
        return new NewPropertyType(apiName, displayName, description, dataType,
                cardinality, isRequired, isTitle, u, constraints, displayOrder);
    }
}
