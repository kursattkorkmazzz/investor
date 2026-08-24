package com.investor.ontology.query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Dinamik ontoloji sorgusu.
 *
 * <p>Şema çalışma zamanında değiştiği için sabit uç noktalar yazamayız; frontend bu
 * yapıyı JSON olarak gönderir ve derleyici güvenli SQL üretir.
 *
 * @param asOf doluysa sorgu "o anda bildiğimiz" hâl üzerinden koşar (tarihsel yol);
 *             boşsa {@code object_current} projeksiyonu kullanılır (hızlı yol)
 */
public record OntologyQuery(
        String type,
        String search,
        List<Filter> where,
        List<SortSpec> orderBy,
        List<Traversal> traverse,
        Instant asOf,
        int limit,
        int offset) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;

    public OntologyQuery {
        where = where == null ? List.of() : List.copyOf(where);
        orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
        traverse = traverse == null ? List.of() : List.copyOf(traverse);
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }
        if (offset < 0) {
            offset = 0;
        }
    }

    public static Builder ofType(String type) {
        return new Builder(type);
    }

    public OntologyQuery withAsOf(Instant knowledgeTime) {
        return new OntologyQuery(type, search, where, orderBy, traverse, knowledgeTime, limit, offset);
    }

    public static final class Builder {

        private final String type;
        private String search;
        private final List<Filter> where = new ArrayList<>();
        private final List<SortSpec> orderBy = new ArrayList<>();
        private final List<Traversal> traverse = new ArrayList<>();
        private Instant asOf;
        private int limit = DEFAULT_LIMIT;
        private int offset;

        private Builder(String type) {
            this.type = type;
        }

        public Builder search(String text) {
            this.search = text;
            return this;
        }

        public Builder where(Filter... filters) {
            this.where.addAll(List.of(filters));
            return this;
        }

        public Builder orderBy(SortSpec... specs) {
            this.orderBy.addAll(List.of(specs));
            return this;
        }

        public Builder traverse(Traversal... traversals) {
            this.traverse.addAll(List.of(traversals));
            return this;
        }

        public Builder asOf(Instant knowledgeTime) {
            this.asOf = knowledgeTime;
            return this;
        }

        public Builder limit(int value) {
            this.limit = value;
            return this;
        }

        public Builder offset(int value) {
            this.offset = value;
            return this;
        }

        public OntologyQuery build() {
            return new OntologyQuery(type, search, where, orderBy, traverse, asOf, limit, offset);
        }
    }
}
