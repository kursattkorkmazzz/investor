package com.investor.ontology.query;

import java.util.List;

import com.investor.ontology.model.ObjectView;

public record QueryResult(List<ObjectView> objects, long total, boolean hasMore) {

    public QueryResult {
        objects = objects == null ? List.of() : List.copyOf(objects);
    }

    public static QueryResult empty() {
        return new QueryResult(List.of(), 0, false);
    }
}
