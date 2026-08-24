package com.investor.api.ontology.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.investor.ontology.model.LinkTypeDef;
import com.investor.ontology.model.LinkView;
import com.investor.ontology.model.ObjectTypeDef;
import com.investor.ontology.model.ObjectView;
import com.investor.ontology.model.PropertyHistoryEntry;
import com.investor.ontology.model.PropertyTypeDef;

/**
 * HTTP taşıma tipleri.
 *
 * <h2>Sayı hassasiyeti</h2>
 * Ondalık değerler JSON'a <em>metin</em> olarak yazılır. JavaScript'in {@code number}'ı
 * IEEE 754 double; 18 ondalıklı token miktarları ve büyük piyasa değerleri sessizce
 * yuvarlanır. Alanın gerçek tipi zaten şema meta verisinde ({@code dataType}) bildiriliyor,
 * frontend değeri {@code decimal.js} ile okuyor.
 */
public final class OntologyDtos {

    private OntologyDtos() {
    }

    // ------------------------------------------------------------------ meta

    public record ObjectTypeResponse(
            UUID id,
            String apiName,
            String displayName,
            String description,
            String icon,
            boolean isAbstract,
            String parentTypeApiName,
            int currentVersion,
            List<PropertyTypeResponse> properties) {

        public static ObjectTypeResponse from(ObjectTypeDef type) {
            return new ObjectTypeResponse(type.id(), type.apiName(), type.displayName(),
                    type.description(), type.icon(), type.isAbstract(), type.parentTypeApiName(),
                    type.currentVersion(),
                    type.properties().stream().map(PropertyTypeResponse::from).toList());
        }
    }

    public record PropertyTypeResponse(
            UUID id,
            String apiName,
            String displayName,
            String description,
            String dataType,
            String cardinality,
            boolean required,
            boolean title,
            String unit,
            Map<String, Object> constraints,
            boolean deprecated,
            int displayOrder) {

        public static PropertyTypeResponse from(PropertyTypeDef p) {
            return new PropertyTypeResponse(p.id(), p.apiName(), p.displayName(), p.description(),
                    p.dataType().name(), p.cardinality().name(), p.isRequired(), p.isTitle(),
                    p.unit(), p.constraints(), p.isDeprecated(), p.displayOrder());
        }
    }

    public record LinkTypeResponse(
            UUID id,
            String apiName,
            String displayName,
            String reverseApiName,
            String reverseDisplayName,
            String fromTypeApiName,
            String toTypeApiName,
            String cardinality,
            boolean symmetric) {

        public static LinkTypeResponse from(LinkTypeDef lt) {
            return new LinkTypeResponse(lt.id(), lt.apiName(), lt.displayName(), lt.reverseApiName(),
                    lt.reverseDisplayName(), lt.fromTypeApiName(), lt.toTypeApiName(),
                    lt.cardinality().name(), lt.isSymmetric());
        }
    }

    // -------------------------------------------------------------- instance

    public record ObjectResponse(
            UUID objectId,
            String typeApiName,
            String externalId,
            String title,
            Instant knowledgeTime,
            Map<String, Object> data,
            Map<String, List<LinkResponse>> links) {

        public static ObjectResponse from(ObjectView view) {
            Map<String, List<LinkResponse>> links = new LinkedHashMap<>();
            view.links().forEach((name, list) ->
                    links.put(name, list.stream().map(LinkResponse::from).toList()));
            return new ObjectResponse(view.objectId(), view.typeApiName(), view.externalId(),
                    view.title(), view.knowledgeTime(), jsonSafe(view.data()), links);
        }
    }

    public record LinkResponse(
            String linkApiName,
            UUID targetObjectId,
            String targetTypeApiName,
            String targetExternalId,
            String targetTitle,
            String weight,
            Map<String, Object> properties,
            Instant validFrom,
            Instant validTo) {

        public static LinkResponse from(LinkView link) {
            return new LinkResponse(link.linkApiName(), link.targetObjectId(),
                    link.targetTypeApiName(), link.targetExternalId(), link.targetTitle(),
                    link.weight() == null ? null : link.weight().toPlainString(),
                    link.properties(), link.validFrom(), link.validTo());
        }
    }

    /**
     * Alan geçmişindeki tek kayıt.
     *
     * <p>{@code retractedAt} dolu kayıtlar cevaptan çıkarılmaz: sistemin bir dönem yanlış
     * bilgiyle çalıştığı, denetim ekranında görünür olmalıdır.
     */
    public record HistoryEntryResponse(
            long valueId,
            String propertyApiName,
            int ordinal,
            Object value,
            Instant validFrom,
            Instant validTo,
            Instant recordedAt,
            Instant retractedAt,
            UUID commitId,
            String actorType,
            String actorId,
            String reason,
            String source,
            String confidence) {

        public static HistoryEntryResponse from(PropertyHistoryEntry e) {
            return new HistoryEntryResponse(e.valueId(), e.propertyApiName(), e.ordinal(),
                    jsonSafe(e.value()), e.validFrom(), e.validTo(), e.recordedAt(), e.retractedAt(),
                    e.commitId(), e.actorType().name(), e.actorId(), e.reason(), e.sourceName(),
                    e.confidence() == null ? null : e.confidence().toPlainString());
        }
    }

    public record QueryResponse(List<ObjectResponse> objects, long total, boolean hasMore) {
    }

    // ------------------------------------------------------------- yardımcı

    private static Map<String, Object> jsonSafe(Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        data.forEach((k, v) -> result.put(k, jsonSafe(v)));
        return result;
    }

    private static Object jsonSafe(Object value) {
        if (value instanceof BigDecimal d) {
            return d.toPlainString();
        }
        if (value instanceof List<?> list) {
            List<Object> mapped = new ArrayList<>(list.size());
            list.forEach(item -> mapped.add(jsonSafe(item)));
            return mapped;
        }
        return value;
    }
}
