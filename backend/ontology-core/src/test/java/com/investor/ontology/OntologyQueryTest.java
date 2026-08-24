package com.investor.ontology;

import java.time.Instant;
import java.util.List;

import com.investor.ontology.model.DataType;
import com.investor.ontology.model.NewObjectType;
import com.investor.ontology.model.NewPropertyType;
import com.investor.ontology.model.ObjectRef;
import com.investor.ontology.model.ObjectView;
import com.investor.ontology.model.Values;
import com.investor.ontology.query.Filter;
import com.investor.ontology.query.OntologyQuery;
import com.investor.ontology.query.QueryResult;
import com.investor.ontology.query.SortSpec;
import com.investor.ontology.support.AbstractOntologyTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Dinamik sorgu DSL'i")
class OntologyQueryTest extends AbstractOntologyTest {

    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant JUN = Instant.parse("2026-06-01T00:00:00Z");

    @BeforeEach
    void setUpOntology() {
        // Asset (soyut) -> CryptoAsset: kalıtımın sorgu tarafında da çalıştığını görmek için
        store.defineObjectType(new NewObjectType("Asset", "Varlık", null, null, true, null), ctx("şema"));
        store.defineProperty("Asset", NewPropertyType.of("name", "Ad", DataType.STRING).asTitle(), ctx("şema"));

        store.defineObjectType(new NewObjectType("CryptoAsset", "Kripto Varlık", null, null, false, "Asset"),
                ctx("şema"));
        store.defineProperty("CryptoAsset", NewPropertyType.of("marketCap", "Piyasa Değeri", DataType.DECIMAL),
                ctx("şema"));
        store.defineProperty("CryptoAsset", NewPropertyType.of("category", "Kategori", DataType.ENUM)
                .withConstraints(java.util.Map.of("enumValues", List.of("L1", "L2", "STABLECOIN"))), ctx("şema"));
        store.defineProperty("CryptoAsset", NewPropertyType.of("tags", "Etiketler", DataType.STRING).asList(),
                ctx("şema"));

        clock.setTo(JAN);
        asset("BINANCE:BTC", "Bitcoin", "1300000000000", "L1", List.of("PoW", "STORE_OF_VALUE"));
        asset("BINANCE:ETH", "Ethereum", "400000000000", "L1", List.of("PoS", "SMART_CONTRACT"));
        asset("BINANCE:ARB", "Arbitrum", "3000000000", "L2", List.of("PoS", "ROLLUP"));
        asset("BINANCE:USDT", "Tether", "120000000000", "STABLECOIN", List.of("STABLE"));
    }

    @Test
    @DisplayName("sayısal filtre, sıralama ve sayfalama")
    void numericFilterSortAndPaging() {
        QueryResult result = store.query(OntologyQuery.ofType("CryptoAsset")
                .where(Filter.gt("marketCap", 100_000_000_000L))
                .orderBy(SortSpec.desc("marketCap"))
                .build());

        assertThat(result.objects()).extracting(ObjectView::externalId)
                .containsExactly("BINANCE:BTC", "BINANCE:ETH", "BINANCE:USDT");
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.hasMore()).isFalse();

        QueryResult firstPage = store.query(OntologyQuery.ofType("CryptoAsset")
                .orderBy(SortSpec.desc("marketCap")).limit(2).build());
        assertThat(firstPage.objects()).hasSize(2);
        assertThat(firstPage.total()).isEqualTo(4);
        assertThat(firstPage.hasMore()).isTrue();

        QueryResult secondPage = store.query(OntologyQuery.ofType("CryptoAsset")
                .orderBy(SortSpec.desc("marketCap")).limit(2).offset(2).build());
        assertThat(secondPage.objects()).extracting(ObjectView::externalId)
                .containsExactly("BINANCE:USDT", "BINANCE:ARB");
        assertThat(secondPage.hasMore()).isFalse();
    }

    @Test
    @DisplayName("enum IN, metin CONTAINS ve başlık araması")
    void textFilters() {
        assertThat(store.query(OntologyQuery.ofType("CryptoAsset")
                .where(Filter.in("category", List.of("L1", "L2"))).build()).objects())
                .extracting(ObjectView::externalId)
                .containsExactlyInAnyOrder("BINANCE:BTC", "BINANCE:ETH", "BINANCE:ARB");

        assertThat(store.query(OntologyQuery.ofType("CryptoAsset")
                .where(Filter.contains("name", "ere")).build()).objects())
                .extracting(ObjectView::externalId)
                .containsExactly("BINANCE:ETH");

        assertThat(store.query(OntologyQuery.ofType("CryptoAsset").search("bitcoin").build()).objects())
                .as("başlık araması büyük/küçük harf duyarsız")
                .extracting(ObjectView::externalId)
                .containsExactly("BINANCE:BTC");
    }

    @Test
    @DisplayName("LIST alanında containment sorgusu")
    void listContainment() {
        assertThat(store.query(OntologyQuery.ofType("CryptoAsset")
                .where(Filter.contains("tags", "PoS")).build()).objects())
                .extracting(ObjectView::externalId)
                .containsExactlyInAnyOrder("BINANCE:ETH", "BINANCE:ARB");
    }

    @Test
    @DisplayName("üst tip sorgusu alt tip nesnelerini de getirir")
    void queryingParentTypeIncludesSubtypes() {
        assertThat(store.query(OntologyQuery.ofType("Asset").build()).objects())
                .as("Asset soyut ama alt tipinin nesneleri gelir")
                .hasSize(4);
    }

    @Test
    @DisplayName("as-of sorgusu filtreleri o günkü değerlere uygular")
    void asOfQueryUsesHistoricalValues() {
        // Haziran'da ARB'nin piyasa değeri 10 katına çıkıyor
        clock.setTo(JUN);
        ObjectRef arb = store.findByExternalId("CryptoAsset", "BINANCE:ARB").orElseThrow();
        store.setProperty(arb, "marketCap", Values.number("30000000000"), JUN, ctx("haziran"));

        OntologyQuery big = OntologyQuery.ofType("CryptoAsset")
                .where(Filter.gt("marketCap", 10_000_000_000L))
                .orderBy(SortSpec.desc("marketCap"))
                .build();

        assertThat(store.query(big.withAsOf(Instant.parse("2026-03-01T00:00:00Z"))).objects())
                .as("martta ARB henüz küçüktü")
                .extracting(ObjectView::externalId)
                .containsExactly("BINANCE:BTC", "BINANCE:ETH", "BINANCE:USDT");

        assertThat(store.query(big.withAsOf(Instant.parse("2026-08-01T00:00:00Z"))).objects())
                .as("ağustosta ARB de eşiği geçiyor")
                .extracting(ObjectView::externalId)
                .containsExactly("BINANCE:BTC", "BINANCE:ETH", "BINANCE:USDT", "BINANCE:ARB");
    }

    @Test
    @DisplayName("snapshot, çağıranın verdiği asOf'u yok sayıp kendi bilgi zamanını dayatır")
    void snapshotPinsKnowledgeTime() {
        clock.setTo(JUN);
        ObjectRef arb = store.findByExternalId("CryptoAsset", "BINANCE:ARB").orElseThrow();
        store.setProperty(arb, "marketCap", Values.number("30000000000"), JUN, ctx("haziran"));

        OntologySnapshot march = store.snapshot(Instant.parse("2026-03-01T00:00:00Z"));

        // Ajan "bana güncel veriyi ver" dese bile snapshot bunu vermez.
        QueryResult result = march.query(OntologyQuery.ofType("CryptoAsset")
                .where(Filter.gt("marketCap", 10_000_000_000L))
                .asOf(Instant.parse("2026-12-01T00:00:00Z"))
                .build());

        assertThat(result.objects()).extracting(ObjectView::externalId)
                .doesNotContain("BINANCE:ARB");
        assertThat(march.object("CryptoAsset", "BINANCE:ARB").orElseThrow().getNumber("marketCap").orElseThrow())
                .isEqualByComparingTo("3000000000");
    }

    @Test
    @DisplayName("bilinmeyen alan ve uyumsuz operatör SQL'e ulaşmadan reddedilir")
    void invalidQueriesAreRejectedBeforeSql() {
        assertThatThrownBy(() -> store.query(OntologyQuery.ofType("CryptoAsset")
                .where(Filter.eq("olmayanAlan", 1)).build()))
                .isInstanceOf(OntologyException.NotFound.class);

        assertThatThrownBy(() -> store.query(OntologyQuery.ofType("CryptoAsset")
                .where(Filter.gt("category", "L1")).build()))
                .isInstanceOf(OntologyException.SchemaViolation.class);

        assertThatThrownBy(() -> store.query(OntologyQuery.ofType("YokBöyleTip").build()))
                .isInstanceOf(OntologyException.NotFound.class);

        assertThatThrownBy(() -> store.query(OntologyQuery.ofType("CryptoAsset")
                .where(Filter.gt("tags", "x")).build()))
                .as("LIST alanında sıralama karşılaştırması yok")
                .isInstanceOf(OntologyException.SchemaViolation.class);
    }

    // ------------------------------------------------------------------

    private void asset(String externalId, String name, String marketCap, String category, List<String> tags) {
        ObjectRef ref = store.createObject("CryptoAsset", externalId, ctx("kurulum"));
        store.setProperties(ref, java.util.Map.of(
                "name", Values.text(name),
                "marketCap", Values.number(marketCap),
                "category", Values.text(category)), JAN, ctx("kurulum"));
        store.setPropertyList(ref, "tags", tags.stream().map(Values::text).toList(), JAN, ctx("kurulum"));
    }
}
