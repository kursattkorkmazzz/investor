package com.investor.ontology.query;

public record SortSpec(String field, Direction direction) {

    public enum Direction {
        ASC,
        DESC
    }

    public static SortSpec asc(String field) {
        return new SortSpec(field, Direction.ASC);
    }

    public static SortSpec desc(String field) {
        return new SortSpec(field, Direction.DESC);
    }
}
