package com.investor.ontology.model;

import java.util.UUID;

/**
 * Bir ontoloji nesnesine hafif referans.
 *
 * <p>{@code typeApiName} sadece kolaylık için taşınır; kimlik tamamen {@code id}'dedir.
 */
public record ObjectRef(UUID id, String typeApiName, String externalId) {

    public ObjectRef {
        if (id == null) {
            throw new IllegalArgumentException("nesne id'si null olamaz");
        }
    }

    public static ObjectRef of(UUID id) {
        return new ObjectRef(id, null, null);
    }
}
