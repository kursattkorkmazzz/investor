package com.investor.ontology.model;

/** Yeni bir nesne tipi tanımlama komutu. */
public record NewObjectType(
        String apiName,
        String displayName,
        String description,
        String icon,
        boolean isAbstract,
        String parentTypeApiName) {

    public static NewObjectType of(String apiName, String displayName) {
        return new NewObjectType(apiName, displayName, null, null, false, null);
    }
}
