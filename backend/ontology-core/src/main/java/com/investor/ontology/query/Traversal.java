package com.investor.ontology.query;

import java.util.List;

/**
 * Sonuç kümesindeki nesnelerden ilişki üzerinden gezinme.
 *
 * @param select hedef nesneden getirilecek alanlar; boşsa yalnızca başlık ve kimlik gelir
 */
public record Traversal(String linkApiName, String as, List<String> select) {

    public Traversal {
        if (linkApiName == null || linkApiName.isBlank()) {
            throw new IllegalArgumentException("ilişki adı zorunlu");
        }
        select = select == null ? List.of() : List.copyOf(select);
    }

    public static Traversal of(String linkApiName) {
        return new Traversal(linkApiName, linkApiName, List.of());
    }
}
