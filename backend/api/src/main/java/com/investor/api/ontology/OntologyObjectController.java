package com.investor.api.ontology;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.investor.api.ontology.dto.OntologyDtos.HistoryEntryResponse;
import com.investor.api.ontology.dto.OntologyDtos.LinkResponse;
import com.investor.api.ontology.dto.OntologyDtos.ObjectResponse;
import com.investor.api.ontology.dto.OntologyRequests.CloseOrRetractRequest;
import com.investor.api.ontology.dto.OntologyRequests.CreateLinkRequest;
import com.investor.api.ontology.dto.OntologyRequests.CreateObjectRequest;
import com.investor.api.ontology.dto.OntologyRequests.RemoveLinkRequest;
import com.investor.api.ontology.dto.OntologyRequests.UpdatePropertiesRequest;
import com.investor.ontology.OntologyException;
import com.investor.ontology.OntologyStore;
import com.investor.ontology.model.ActorType;
import com.investor.ontology.model.Cardinality;
import com.investor.ontology.model.CommitContext;
import com.investor.ontology.model.LinkProperties;
import com.investor.ontology.model.ObjectRef;
import com.investor.ontology.model.ObjectTypeDef;
import com.investor.ontology.model.ObjectView;
import com.investor.ontology.model.PropertyTypeDef;
import com.investor.ontology.model.Value;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Nesne okuma/yazma uç noktaları. */
@RestController
@RequestMapping("/api/ontology/objects")
public class OntologyObjectController {

    private final OntologyStore store;
    private final ValueParser valueParser;
    private final Clock clock;

    public OntologyObjectController(OntologyStore store, ValueParser valueParser, Clock clock) {
        this.store = store;
        this.valueParser = valueParser;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ okuma

    /**
     * @param asOf verilirse "o anda bildiğimiz" hâl döner; verilmezse bugünkü en iyi bilgimiz.
     */
    @GetMapping("/{id}")
    public ObjectResponse get(@PathVariable UUID id, @RequestParam(required = false) Instant asOf) {
        ObjectRef ref = ObjectRef.of(id);
        ObjectView view = (asOf == null ? store.current(ref) : store.asOf(ref, asOf))
                .orElseThrow(() -> notFound(id, asOf));
        return ObjectResponse.from(view);
    }

    @GetMapping("/by-external-id")
    public ObjectResponse getByExternalId(@RequestParam String type,
                                          @RequestParam String externalId,
                                          @RequestParam(required = false) Instant asOf) {
        ObjectRef ref = store.findByExternalId(type, externalId)
                .orElseThrow(() -> new OntologyException.NotFound(
                        "Nesne bulunamadı: %s/%s".formatted(type, externalId)));
        return get(ref.id(), asOf);
    }

    /** Alanın tüm geçmişi — geri çekilmiş kayıtlar dahil. */
    @GetMapping("/{id}/history/{property}")
    public List<HistoryEntryResponse> history(@PathVariable UUID id, @PathVariable String property) {
        return store.history(ObjectRef.of(id), property).stream()
                .map(HistoryEntryResponse::from)
                .toList();
    }

    @GetMapping("/{id}/links")
    public List<LinkResponse> links(@PathVariable UUID id, @RequestParam(required = false) Instant asOf) {
        return store.links(ObjectRef.of(id), asOf).stream().map(LinkResponse::from).toList();
    }

    // ------------------------------------------------------------------ yazma

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectResponse create(@Valid @RequestBody CreateObjectRequest request) {
        Instant validFrom = request.validFrom() == null ? clock.instant() : request.validFrom();
        Map<String, Value> single = new LinkedHashMap<>();
        Map<String, List<Value>> lists = new LinkedHashMap<>();
        split(request.typeApiName(), ValueParser.orEmpty(request.values()), single, lists);

        CommitContext ctx = store.openCommit(ctx(request.reason(), "nesne oluşturuldu"));
        ObjectRef ref = store.createObject(request.typeApiName(), request.externalId(), single, validFrom, ctx);
        lists.forEach((property, values) -> store.setPropertyList(ref, property, values, validFrom, ctx));

        return ObjectResponse.from(store.current(ref).orElseThrow());
    }

    /**
     * Alan(lar)ı günceller.
     *
     * <p>Eski değerler ezilmez: geçerlilik aralıkları kapatılır, yeni satırlar eklenir.
     * Tüm alanlar tek commit altında yazılır — "bunlar aynı anda, aynı sebeple değişti"
     * bilgisi korunur.
     */
    @PatchMapping("/{id}")
    public ObjectResponse update(@PathVariable UUID id, @Valid @RequestBody UpdatePropertiesRequest request) {
        ObjectRef ref = resolve(id);
        Instant validFrom = request.validFrom() == null ? clock.instant() : request.validFrom();

        Map<String, Value> single = new LinkedHashMap<>();
        Map<String, List<Value>> lists = new LinkedHashMap<>();
        split(ref.typeApiName(), request.values(), single, lists);

        CommitContext ctx = store.openCommit(ctx(request.reason(), "alan güncellendi"));
        if (!single.isEmpty()) {
            store.setProperties(ref, single, validFrom, ctx);
        }
        lists.forEach((property, values) -> store.setPropertyList(ref, property, values, validFrom, ctx));

        return ObjectResponse.from(store.current(ref).orElseThrow());
    }

    /** "Bu değer artık doğru değil." Geçmiş olduğu gibi kalır. */
    @PostMapping("/{id}/properties/{property}/close")
    public ObjectResponse close(@PathVariable UUID id, @PathVariable String property,
                                @RequestBody(required = false) CloseOrRetractRequest request) {
        ObjectRef ref = resolve(id);
        Instant validTo = request == null || request.validTo() == null ? clock.instant() : request.validTo();
        store.closeProperty(ref, property, validTo,
                ctx(request == null ? null : request.reason(), "alan kapatıldı"));
        return ObjectResponse.from(store.current(ref).orElseThrow());
    }

    /** "Bu kaydı hiç yapmamalıydık." Satır silinmez, geri çekilmiş olarak damgalanır. */
    @PostMapping("/{id}/properties/{property}/retract")
    public ObjectResponse retract(@PathVariable UUID id, @PathVariable String property,
                                  @RequestBody(required = false) CloseOrRetractRequest request) {
        ObjectRef ref = resolve(id);
        store.retractProperty(ref, property,
                ctx(request == null ? null : request.reason(), "kayıt geri çekildi"));
        return ObjectResponse.from(store.current(ref).orElseThrow());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        store.deleteObject(resolve(id), ctx(null, "nesne silindi"));
    }

    @PostMapping("/{id}/links")
    @ResponseStatus(HttpStatus.CREATED)
    public List<LinkResponse> addLink(@PathVariable UUID id, @Valid @RequestBody CreateLinkRequest request) {
        ObjectRef from = resolve(id);
        ObjectRef to = resolve(request.targetObjectId());
        Instant validFrom = request.validFrom() == null ? clock.instant() : request.validFrom();
        LinkProperties props = new LinkProperties(
                request.weight() == null ? null : new BigDecimal(request.weight()),
                ValueParser.orEmpty(request.properties()));

        store.link(from, request.linkApiName(), to, props, validFrom, ctx(request.reason(), "ilişki kuruldu"));
        return links(id, null);
    }

    @DeleteMapping("/{id}/links")
    public List<LinkResponse> removeLink(@PathVariable UUID id, @Valid @RequestBody RemoveLinkRequest request) {
        ObjectRef from = resolve(id);
        ObjectRef to = resolve(request.targetObjectId());
        Instant validTo = request.validTo() == null ? clock.instant() : request.validTo();
        store.unlink(from, request.linkApiName(), to, validTo, ctx(request.reason(), "ilişki kapatıldı"));
        return links(id, null);
    }

    // ------------------------------------------------------------------

    /** LIST ve SINGLE alanları ayırır; ikisi farklı yazma yolları kullanıyor. */
    private void split(String typeApiName, Map<String, Object> raw,
                       Map<String, Value> single, Map<String, List<Value>> lists) {
        ObjectTypeDef type = store.objectType(typeApiName)
                .orElseThrow(() -> new OntologyException.NotFound("Tip bulunamadı: " + typeApiName));
        raw.forEach((name, value) -> {
            PropertyTypeDef property = resolveProperty(type, name);
            if (property.cardinality() == Cardinality.LIST) {
                lists.put(name, valueParser.parseList(property, value));
            } else {
                single.put(name, valueParser.parse(property, value));
            }
        });
    }

    /** Alan kalıtılmış olabilir; tip hiyerarşisini yukarı tara. */
    private PropertyTypeDef resolveProperty(ObjectTypeDef type, String propertyApiName) {
        ObjectTypeDef cursor = type;
        while (cursor != null) {
            var found = cursor.property(propertyApiName);
            if (found.isPresent()) {
                return found.get();
            }
            cursor = cursor.parentTypeApiName() == null
                    ? null
                    : store.objectType(cursor.parentTypeApiName()).orElse(null);
        }
        throw new OntologyException.NotFound(
                "'%s' tipinde '%s' alanı yok".formatted(type.apiName(), propertyApiName));
    }

    private ObjectRef resolve(UUID id) {
        return store.current(ObjectRef.of(id))
                .map(ObjectView::ref)
                .orElseThrow(() -> notFound(id, null));
    }

    private static OntologyException.NotFound notFound(UUID id, Instant asOf) {
        return new OntologyException.NotFound(asOf == null
                ? "Nesne bulunamadı: " + id
                : "Nesne %s anında bulunamadı: %s".formatted(asOf, id));
    }

    private static CommitContext ctx(String reason, String fallback) {
        return new CommitContext(ActorType.HUMAN, "api", reason == null ? fallback : reason,
                null, null, null, null);
    }
}
