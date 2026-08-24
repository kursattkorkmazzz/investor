package com.investor.ontology.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.investor.ontology.OntologySnapshot;
import com.investor.ontology.OntologyStore;
import com.investor.ontology.model.LinkView;
import com.investor.ontology.model.ObjectRef;
import com.investor.ontology.model.ObjectView;
import com.investor.ontology.query.OntologyQuery;
import com.investor.ontology.query.QueryResult;

/**
 * Bilgi zamanına sabitlenmiş görünüm.
 *
 * <p>Okumalar tembel yapılır ama tur boyunca cache'lenir: aynı nesne iki kez sorulduğunda
 * iki farklı cevap dönmesi mümkün değildir. Analiz turunun tekrar üretilebilirliği buna dayanır.
 *
 * <p>{@link #query} çağrısında çağıranın verdiği {@code asOf} yok sayılır ve snapshot'ın
 * bilgi zamanı zorlanır — bir ajanın kazara "şu anki" veriye ulaşması engellenir.
 */
final class DefaultOntologySnapshot implements OntologySnapshot {

    private final OntologyStore store;
    private final Instant knowledgeTime;
    private final Map<ObjectRef, Optional<ObjectView>> objectCache = new ConcurrentHashMap<>();
    private final Map<ObjectRef, List<LinkView>> linkCache = new ConcurrentHashMap<>();

    DefaultOntologySnapshot(OntologyStore store, Instant knowledgeTime) {
        if (knowledgeTime == null) {
            throw new IllegalArgumentException("snapshot için bilgi zamanı zorunlu");
        }
        this.store = store;
        this.knowledgeTime = knowledgeTime;
    }

    @Override
    public Instant knowledgeTime() {
        return knowledgeTime;
    }

    @Override
    public Optional<ObjectView> object(ObjectRef ref) {
        return objectCache.computeIfAbsent(ref, r -> store.asOf(r, knowledgeTime));
    }

    @Override
    public Optional<ObjectView> object(String typeApiName, String externalId) {
        return store.findByExternalId(typeApiName, externalId).flatMap(this::object);
    }

    @Override
    public List<LinkView> links(ObjectRef ref) {
        return linkCache.computeIfAbsent(ref, r -> store.links(r, knowledgeTime));
    }

    @Override
    public QueryResult query(OntologyQuery query) {
        return store.query(query.withAsOf(knowledgeTime));
    }

    @Override
    public String toString() {
        return "OntologySnapshot[" + knowledgeTime + "]";
    }
}
