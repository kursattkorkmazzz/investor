package com.investor.api.ontology;

import java.util.ArrayList;
import java.util.List;

import com.investor.api.ontology.dto.OntologyDtos.ObjectResponse;
import com.investor.api.ontology.dto.OntologyDtos.QueryResponse;
import com.investor.api.ontology.dto.OntologyRequests.QueryRequest;
import com.investor.ontology.OntologyStore;
import com.investor.ontology.query.Filter;
import com.investor.ontology.query.OntologyQuery;
import com.investor.ontology.query.Operator;
import com.investor.ontology.query.QueryResult;
import com.investor.ontology.query.SortSpec;
import com.investor.ontology.query.Traversal;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dinamik sorgu uç noktası.
 *
 * <p>Şema çalışma zamanında değiştiği için tip başına sabit uç nokta üretilemiyor.
 * Gövdedeki alan adları ve operatörler ontoloji şemasına karşı çözülür; çözülemeyen
 * bir alan SQL'e ulaşmadan reddedilir.
 */
@RestController
@RequestMapping("/api/ontology/query")
public class OntologyQueryController {

    private final OntologyStore store;

    public OntologyQueryController(OntologyStore store) {
        this.store = store;
    }

    @PostMapping
    public QueryResponse query(@Valid @RequestBody QueryRequest request) {
        List<Filter> filters = new ArrayList<>();
        if (request.where() != null) {
            request.where().forEach(f -> filters.add(new Filter(f.field(),
                    OntologySchemaController.parseEnum(Operator.class, f.op(), "op"), f.value())));
        }

        List<SortSpec> sorts = new ArrayList<>();
        if (request.orderBy() != null) {
            request.orderBy().forEach(s -> sorts.add(new SortSpec(s.field(),
                    s.direction() == null
                            ? SortSpec.Direction.ASC
                            : OntologySchemaController.parseEnum(
                                    SortSpec.Direction.class, s.direction(), "direction"))));
        }

        List<Traversal> traversals = new ArrayList<>();
        if (request.traverse() != null) {
            request.traverse().forEach(t -> traversals.add(
                    new Traversal(t.link(), t.as() == null ? t.link() : t.as(), t.select())));
        }

        OntologyQuery query = new OntologyQuery(
                request.type(), request.search(), filters, sorts, traversals, request.asOf(),
                request.limit() == null ? OntologyQuery.DEFAULT_LIMIT : request.limit(),
                request.offset() == null ? 0 : request.offset());

        QueryResult result = store.query(query);
        return new QueryResponse(
                result.objects().stream().map(ObjectResponse::from).toList(),
                result.total(),
                result.hasMore());
    }
}
