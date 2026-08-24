package com.investor.ontology;

import java.math.BigDecimal;
import java.time.Instant;

import com.investor.ontology.model.DataType;
import com.investor.ontology.model.LinkCardinality;
import com.investor.ontology.model.LinkProperties;
import com.investor.ontology.model.NewLinkType;
import com.investor.ontology.model.NewObjectType;
import com.investor.ontology.model.NewPropertyType;
import com.investor.ontology.model.ObjectRef;
import com.investor.ontology.model.Values;
import com.investor.ontology.support.AbstractOntologyTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("İlişkiler")
class OntologyLinkTest extends AbstractOntologyTest {

    private ObjectRef btc;
    private ObjectRef eth;
    private ObjectRef binance;
    private ObjectRef kraken;

    @BeforeEach
    void setUpOntology() {
        store.defineObjectType(NewObjectType.of("CryptoAsset", "Kripto Varlık"), ctx("şema"));
        store.defineObjectType(NewObjectType.of("Exchange", "Borsa"), ctx("şema"));
        store.defineProperty("CryptoAsset", NewPropertyType.of("name", "Ad", DataType.STRING).asTitle(), ctx("şema"));
        store.defineProperty("Exchange", NewPropertyType.of("name", "Ad", DataType.STRING).asTitle(), ctx("şema"));

        // Bir varlık tek borsada listelenir (bu test için); borsada çok varlık olur.
        store.defineLinkType(NewLinkType.of("LISTED_ON", "Listelendiği borsa",
                "LISTS", "Listelediği varlıklar",
                "CryptoAsset", "Exchange", LinkCardinality.MANY_TO_ONE), ctx("şema"));

        store.defineLinkType(new NewLinkType("COMPETES_WITH", "Rakip",
                "COMPETES_WITH", "Rakip",
                "CryptoAsset", "CryptoAsset", LinkCardinality.MANY_TO_MANY, true, java.util.Map.of()),
                ctx("şema"));

        btc = createAsset("BINANCE:BTC", "Bitcoin");
        eth = createAsset("BINANCE:ETH", "Ethereum");
        binance = createExchange("BINANCE", "Binance");
        kraken = createExchange("KRAKEN", "Kraken");
    }

    @Test
    @DisplayName("ilişkiler de bitemporal: geçmişte hangi bağ vardı sorusu cevaplanır")
    void linksAreBitemporal() {
        Instant feb = Instant.parse("2026-02-01T00:00:00Z");
        Instant jun = Instant.parse("2026-06-01T00:00:00Z");

        clock.setTo(feb);
        store.link(btc, "LISTED_ON", binance, LinkProperties.none(), feb, ctx("listelendi"));

        clock.setTo(jun);
        store.unlink(btc, "LISTED_ON", binance, jun, ctx("listeden çıktı"));

        assertThat(store.links(btc, Instant.parse("2026-04-01T00:00:00Z")))
                .as("nisanda bağ vardı")
                .singleElement()
                .satisfies(link -> assertThat(link.targetObjectId()).isEqualTo(binance.id()));

        assertThat(store.links(btc, Instant.parse("2026-08-01T00:00:00Z")))
                .as("ağustosta bağ yok")
                .isEmpty();

        assertThat(store.links(btc, null)).as("güncel durumda da yok").isEmpty();
    }

    @Test
    @DisplayName("MANY_TO_ONE: kaynağın yeni bağı eskisini kapatır, silmez")
    void singleTargetPerSourceClosesPrevious() {
        Instant feb = Instant.parse("2026-02-01T00:00:00Z");
        Instant may = Instant.parse("2026-05-01T00:00:00Z");

        clock.setTo(feb);
        store.link(btc, "LISTED_ON", binance, LinkProperties.none(), feb, ctx("binance"));
        clock.setTo(may);
        store.link(btc, "LISTED_ON", kraken, LinkProperties.none(), may, ctx("kraken'e taşındı"));

        assertThat(store.links(btc, null))
                .as("güncelde tek bağ: kraken")
                .singleElement()
                .satisfies(link -> assertThat(link.targetObjectId()).isEqualTo(kraken.id()));

        assertThat(store.links(btc, Instant.parse("2026-03-01T00:00:00Z")))
                .as("martta binance'teydi — geçmiş silinmedi")
                .singleElement()
                .satisfies(link -> assertThat(link.targetObjectId()).isEqualTo(binance.id()));
    }

    @Test
    @DisplayName("simetrik ilişki iki yönde de kurulur ve iki yönde de kapanır")
    void symmetricLinksGoBothWays() {
        Instant feb = Instant.parse("2026-02-01T00:00:00Z");
        clock.setTo(feb);
        store.link(btc, "COMPETES_WITH", eth, LinkProperties.weighted(new BigDecimal("0.65")), feb, ctx("rakip"));

        assertThat(store.links(btc, null)).singleElement()
                .satisfies(link -> {
                    assertThat(link.targetObjectId()).isEqualTo(eth.id());
                    assertThat(link.weight()).isEqualByComparingTo("0.65");
                });
        assertThat(store.links(eth, null))
                .as("ters yön de kurulmuş olmalı")
                .singleElement()
                .satisfies(link -> assertThat(link.targetObjectId()).isEqualTo(btc.id()));

        Instant sep = Instant.parse("2026-09-01T00:00:00Z");
        clock.setTo(sep);
        store.unlink(btc, "COMPETES_WITH", eth, sep, ctx("artık rakip değil"));

        assertThat(store.links(btc, null)).isEmpty();
        assertThat(store.links(eth, null)).as("ters yön de kapanmalı").isEmpty();
    }

    @Test
    @DisplayName("güncel görünüm ilişki özetini taşır")
    void currentViewCarriesLinkSummary() {
        Instant feb = Instant.parse("2026-02-01T00:00:00Z");
        clock.setTo(feb);
        store.link(btc, "LISTED_ON", binance, LinkProperties.none(), feb, ctx("listelendi"));

        assertThat(store.current(btc).orElseThrow().linked("LISTED_ON"))
                .singleElement()
                .satisfies(link -> {
                    assertThat(link.targetTypeApiName()).isEqualTo("Exchange");
                    assertThat(link.targetExternalId()).isEqualTo("BINANCE");
                });
    }

    // ------------------------------------------------------------------

    private ObjectRef createAsset(String externalId, String name) {
        ObjectRef ref = store.createObject("CryptoAsset", externalId, ctx("kurulum"));
        store.setProperty(ref, "name", Values.text(name), T0, ctx("kurulum"));
        return ref;
    }

    private ObjectRef createExchange(String externalId, String name) {
        ObjectRef ref = store.createObject("Exchange", externalId, ctx("kurulum"));
        store.setProperty(ref, "name", Values.text(name), T0, ctx("kurulum"));
        return ref;
    }
}
