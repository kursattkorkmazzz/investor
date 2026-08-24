package com.investor.ontology.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bir nesnenin belirli bir bilgi anındaki görünümü.
 *
 * @param knowledgeTime {@code null} ise güncel durum; doluysa "o anda bildiğimiz" hâl
 * @param data          çözülmüş alan değerleri. LIST alanlar {@link List} taşır.
 */
public record ObjectView(
        UUID objectId,
        String typeApiName,
        String externalId,
        String title,
        Instant knowledgeTime,
        Map<String, Object> data,
        Map<String, List<LinkView>> links) {

    public ObjectRef ref() {
        return new ObjectRef(objectId, typeApiName, externalId);
    }

    public boolean isCurrent() {
        return knowledgeTime == null;
    }

    public Optional<Object> get(String property) {
        return Optional.ofNullable(data.get(property));
    }

    public Optional<String> getText(String property) {
        return get(property).filter(String.class::isInstance).map(String.class::cast);
    }

    public Optional<BigDecimal> getNumber(String property) {
        return get(property).filter(BigDecimal.class::isInstance).map(BigDecimal.class::cast);
    }

    public Optional<Boolean> getBoolean(String property) {
        return get(property).filter(Boolean.class::isInstance).map(Boolean.class::cast);
    }

    public Optional<Instant> getTimestamp(String property) {
        return get(property).filter(Instant.class::isInstance).map(Instant.class::cast);
    }

    @SuppressWarnings("unchecked")
    public List<Object> getList(String property) {
        Object v = data.get(property);
        if (v == null) {
            return List.of();
        }
        return v instanceof List<?> list ? (List<Object>) list : List.of(v);
    }

    public List<LinkView> linked(String linkApiName) {
        return links.getOrDefault(linkApiName, List.of());
    }
}
