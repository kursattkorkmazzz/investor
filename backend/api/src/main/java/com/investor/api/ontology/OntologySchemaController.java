package com.investor.api.ontology;

import java.util.List;

import com.investor.api.ontology.dto.OntologyDtos.LinkTypeResponse;
import com.investor.api.ontology.dto.OntologyDtos.ObjectTypeResponse;
import com.investor.api.ontology.dto.OntologyRequests;
import com.investor.api.ontology.dto.OntologyRequests.CreateLinkTypeRequest;
import com.investor.api.ontology.dto.OntologyRequests.CreateObjectTypeRequest;
import com.investor.api.ontology.dto.OntologyRequests.CreatePropertyTypeRequest;
import com.investor.ontology.OntologyException;
import com.investor.ontology.OntologyStore;
import com.investor.ontology.model.ActorType;
import com.investor.ontology.model.Cardinality;
import com.investor.ontology.model.CommitContext;
import com.investor.ontology.model.DataType;
import com.investor.ontology.model.LinkCardinality;
import com.investor.ontology.model.NewLinkType;
import com.investor.ontology.model.NewObjectType;
import com.investor.ontology.model.NewPropertyType;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Şema uç noktaları.
 *
 * <p>Frontend sabit form yazamaz — ontoloji çalışma zamanında değişiyor. Bu uç noktalar
 * şemayı bildirir, arayüz formlarını ondan üretir.
 */
@RestController
@RequestMapping("/api/ontology")
public class OntologySchemaController {

    private final OntologyStore store;

    public OntologySchemaController(OntologyStore store) {
        this.store = store;
    }

    @GetMapping("/types")
    public List<ObjectTypeResponse> types() {
        return store.objectTypes().stream().map(ObjectTypeResponse::from).toList();
    }

    @GetMapping("/types/{apiName}")
    public ObjectTypeResponse type(@PathVariable String apiName) {
        return store.objectType(apiName)
                .map(ObjectTypeResponse::from)
                .orElseThrow(() -> new OntologyException.NotFound("Tip bulunamadı: " + apiName));
    }

    @PostMapping("/types")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectTypeResponse createType(@Valid @RequestBody CreateObjectTypeRequest request) {
        NewObjectType spec = new NewObjectType(request.apiName(), request.displayName(),
                request.description(), request.icon(),
                OntologyRequests.flag(request.isAbstract()), request.parentTypeApiName());
        return ObjectTypeResponse.from(store.defineObjectType(spec, ctx(request.reason(), "tip oluşturuldu")));
    }

    @PostMapping("/types/{typeApiName}/properties")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectTypeResponse addProperty(@PathVariable String typeApiName,
                                          @Valid @RequestBody CreatePropertyTypeRequest request) {
        NewPropertyType spec = new NewPropertyType(
                request.apiName(),
                request.displayName(),
                request.description(),
                parseEnum(DataType.class, request.dataType(), "dataType"),
                request.cardinality() == null
                        ? Cardinality.SINGLE
                        : parseEnum(Cardinality.class, request.cardinality(), "cardinality"),
                OntologyRequests.flag(request.required()),
                OntologyRequests.flag(request.title()),
                request.unit(),
                ValueParser.orEmpty(request.constraints()),
                OntologyRequests.number(request.displayOrder(), 0));
        store.defineProperty(typeApiName, spec, ctx(request.reason(), "alan eklendi"));
        return type(typeApiName);
    }

    /** Alanı kullanımdan kaldırır. Veri silinmez — yalnızca işaretlenir. */
    @DeleteMapping("/types/{typeApiName}/properties/{propertyApiName}")
    public ObjectTypeResponse deprecateProperty(@PathVariable String typeApiName,
                                                @PathVariable String propertyApiName) {
        store.deprecateProperty(typeApiName, propertyApiName, ctx(null, "alan kullanımdan kaldırıldı"));
        return type(typeApiName);
    }

    @GetMapping("/link-types")
    public List<LinkTypeResponse> linkTypes() {
        return store.linkTypes().stream().map(LinkTypeResponse::from).toList();
    }

    @PostMapping("/link-types")
    @ResponseStatus(HttpStatus.CREATED)
    public LinkTypeResponse createLinkType(@Valid @RequestBody CreateLinkTypeRequest request) {
        NewLinkType spec = new NewLinkType(request.apiName(), request.displayName(),
                request.reverseApiName(), request.reverseDisplayName(),
                request.fromTypeApiName(), request.toTypeApiName(),
                parseEnum(LinkCardinality.class, request.cardinality(), "cardinality"),
                OntologyRequests.flag(request.symmetric()), java.util.Map.of());
        return LinkTypeResponse.from(store.defineLinkType(spec, ctx(request.reason(), "ilişki tipi oluşturuldu")));
    }

    static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new OntologyException.SchemaViolation(
                    "'%s' için geçersiz değer '%s'; izin verilenler: %s"
                            .formatted(field, raw, java.util.Arrays.toString(type.getEnumConstants())));
        }
    }

    private static CommitContext ctx(String reason, String fallback) {
        return new CommitContext(ActorType.HUMAN, "api", reason == null ? fallback : reason,
                null, null, null, null, null);
    }
}
