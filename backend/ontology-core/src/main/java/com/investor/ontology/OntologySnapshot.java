package com.investor.ontology;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.investor.ontology.model.LinkView;
import com.investor.ontology.model.ObjectRef;
import com.investor.ontology.model.ObjectView;
import com.investor.ontology.query.OntologyQuery;
import com.investor.ontology.query.QueryResult;

/**
 * Belirli bir bilgi anına sabitlenmiş ontoloji görünümü.
 *
 * <p>Bir analiz turu boyunca dünya değişmez. Bu, hem tekrar üretilebilirliği
 * (aynı girdi &rarr; aynı karar) hem de backtest'in canlı sistemle birebir aynı kod
 * yolunu kullanmasını sağlar.
 *
 * <p>Analiz ajanları {@link OntologyStore}'a değil bu arayüze bağımlıdır; böylece
 * bir ajanın kazara "şu anki" veriye ulaşması mümkün olmaz.
 */
public interface OntologySnapshot {

    Instant knowledgeTime();

    Optional<ObjectView> object(ObjectRef ref);

    Optional<ObjectView> object(String typeApiName, String externalId);

    List<LinkView> links(ObjectRef ref);

    /** Sorgu, snapshot'ın bilgi zamanına zorlanır — çağıranın {@code asOf}'u yok sayılır. */
    QueryResult query(OntologyQuery query);
}
