package com.investor.ontology.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Bir nesne tipinin tam tanımı — alanlarıyla birlikte. */
public record ObjectTypeDef(
        UUID id,
        String apiName,
        String displayName,
        String description,
        String icon,
        boolean isAbstract,
        String parentTypeApiName,
        int currentVersion,
        List<PropertyTypeDef> properties) {

    public Optional<PropertyTypeDef> property(String propertyApiName) {
        return properties.stream().filter(p -> p.apiName().equals(propertyApiName)).findFirst();
    }

    public Optional<PropertyTypeDef> titleProperty() {
        return properties.stream().filter(PropertyTypeDef::isTitle).findFirst();
    }
}
