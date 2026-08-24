package com.investor.api.ontology.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Yazma isteklerinin gövdeleri.
 *
 * <p>Mantıksal ve sayısal alanlar kutulu ({@code Boolean}/{@code Integer}) tutuluyor:
 * istekte bir alanın <em>yokluğu</em> ile {@code false}/{@code 0} olması farklı şeyler.
 * Kutulu tip bu ayrımı kodda görünür kılıyor; varsayılana düşürme {@link #flag} ve
 * {@link #number} üzerinden açıkça yapılıyor.
 */
public final class OntologyRequests {

    private OntologyRequests() {
    }

    /** Belirtilmemiş mantıksal alan {@code false} sayılır. */
    public static boolean flag(Boolean value) {
        return value != null && value;
    }

    /** Belirtilmemiş sayısal alan verilen varsayılana düşer. */
    public static int number(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    public record CreateObjectTypeRequest(
            @NotBlank String apiName,
            @NotBlank String displayName,
            String description,
            String icon,
            Boolean isAbstract,
            String parentTypeApiName,
            String reason) {
    }

    public record CreatePropertyTypeRequest(
            @NotBlank String apiName,
            @NotBlank String displayName,
            String description,
            @NotBlank String dataType,
            String cardinality,
            Boolean required,
            Boolean title,
            String unit,
            Map<String, Object> constraints,
            Integer displayOrder,
            String reason) {
    }

    public record CreateLinkTypeRequest(
            @NotBlank String apiName,
            @NotBlank String displayName,
            @NotBlank String reverseApiName,
            @NotBlank String reverseDisplayName,
            @NotBlank String fromTypeApiName,
            @NotBlank String toTypeApiName,
            @NotBlank String cardinality,
            Boolean symmetric,
            String reason) {
    }

    public record CreateObjectRequest(
            @NotBlank String typeApiName,
            @NotBlank String externalId,
            Map<String, Object> values,
            Instant validFrom,
            String reason) {
    }

    /**
     * Alan güncelleme.
     *
     * @param validFrom değerin gerçek dünyada geçerli olmaya başladığı an. Verilmezse
     *                  "şimdi" kabul edilir. Kayıt zamanı ({@code recorded_at}) istemciden
     *                  alınmaz — sunucu saatinden yazılır; aksi hâlde geçmiş uydurulabilirdi.
     */
    public record UpdatePropertiesRequest(
            @NotNull Map<String, Object> values,
            Instant validFrom,
            String reason) {
    }

    public record CloseOrRetractRequest(Instant validTo, String reason) {
    }

    public record CreateLinkRequest(
            @NotBlank String linkApiName,
            @NotNull UUID targetObjectId,
            String weight,
            Map<String, Object> properties,
            Instant validFrom,
            String reason) {
    }

    public record RemoveLinkRequest(
            @NotBlank String linkApiName,
            @NotNull UUID targetObjectId,
            Instant validTo,
            String reason) {
    }

    public record QueryRequest(
            @NotBlank String type,
            String search,
            List<FilterRequest> where,
            List<SortRequest> orderBy,
            List<TraverseRequest> traverse,
            Instant asOf,
            Integer limit,
            Integer offset) {
    }

    public record FilterRequest(@NotBlank String field, @NotBlank String op, Object value) {
    }

    public record SortRequest(@NotBlank String field, String direction) {
    }

    public record TraverseRequest(@NotBlank String link, String as, List<String> select) {
    }
}
