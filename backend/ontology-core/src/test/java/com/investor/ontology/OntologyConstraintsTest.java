package com.investor.ontology;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.investor.ontology.model.DataType;
import com.investor.ontology.model.NewObjectType;
import com.investor.ontology.model.NewPropertyType;
import com.investor.ontology.model.ObjectRef;
import com.investor.ontology.model.PropertyHistoryEntry;
import com.investor.ontology.model.Values;
import com.investor.ontology.support.AbstractOntologyTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Ontoloji kısıtları ve yazma disiplini")
class OntologyConstraintsTest extends AbstractOntologyTest {

    private static final String TYPE = "CryptoAsset";
    private ObjectRef btc;

    @BeforeEach
    void setUpOntology() {
        store.defineObjectType(NewObjectType.of(TYPE, "Kripto Varlık"), ctx("şema"));
        store.defineProperty(TYPE, NewPropertyType.of("name", "Ad", DataType.STRING).asTitle(), ctx("şema"));
        store.defineProperty(TYPE, NewPropertyType.of("supply", "Arz", DataType.DECIMAL)
                .withConstraints(Map.of("min", 0)), ctx("şema"));
        store.defineProperty(TYPE, NewPropertyType.of("category", "Kategori", DataType.ENUM)
                .withConstraints(Map.of("enumValues", List.of("L1", "L2", "STABLECOIN"))), ctx("şema"));
        store.defineProperty(TYPE, NewPropertyType.of("tags", "Etiketler", DataType.STRING).asList(), ctx("şema"));

        btc = store.createObject(TYPE, "BINANCE:BTC", ctx("kurulum"));
        store.setProperty(btc, "name", Values.text("Bitcoin"), T0, ctx("kurulum"));
    }

    @Test
    @DisplayName("geçmişe yazma çakışması sessizce geçmez, hata verir")
    void overlappingIntervalIsRejected() {
        Instant march = Instant.parse("2026-03-01T00:00:00Z");
        clock.setTo(march);
        store.setProperty(btc, "supply", Values.number("19650000"), march, ctx("mart"));

        // Mart'tan önce başlayan bir aralık yazmak, mevcut açık aralıkla çakışır.
        assertThatThrownBy(() -> store.setProperty(btc, "supply", Values.number("19000000"),
                Instant.parse("2026-02-01T00:00:00Z"), ctx("geriye dönük yazma")))
                .isInstanceOf(OntologyException.TemporalConflict.class)
                .hasMessageContaining("çakışıyor");
    }

    @Test
    @DisplayName("aynı anda yapılan düzeltme, eski kaydı geri çeker")
    void sameInstantCorrectionRetractsPreviousRecord() {
        Instant march = Instant.parse("2026-03-01T00:00:00Z");
        clock.setTo(march);
        store.setProperty(btc, "supply", Values.number("19650000"), march, ctx("mart"));

        clock.setTo(Instant.parse("2026-03-01T06:00:00Z"));
        store.setProperty(btc, "supply", Values.number("19655000"), march, ctx("aynı an için düzeltme"));

        List<PropertyHistoryEntry> history = store.history(btc, "supply");
        assertThat(history).hasSize(2);
        assertThat(history).filteredOn(PropertyHistoryEntry::isRetracted).hasSize(1);
        assertThat(store.current(btc).orElseThrow().getNumber("supply").orElseThrow())
                .isEqualByComparingTo("19655000");
    }

    @Test
    @DisplayName("değişmemiş değeri tekrar yazmak geçmişe gürültü eklemez")
    void rewritingSameValueIsNoOp() {
        clock.setTo(Instant.parse("2026-02-01T00:00:00Z"));
        store.setProperty(btc, "supply", Values.number("19500000"), Instant.parse("2026-02-01T00:00:00Z"),
                ctx("ilk"));

        // Ingest hattı aynı veriyi tekrar getirdi — ölçek farklı yazılmış olsa bile aynı sayı.
        clock.setTo(Instant.parse("2026-02-02T00:00:00Z"));
        store.setProperty(btc, "supply", Values.number("19500000.00"), Instant.parse("2026-02-02T00:00:00Z"),
                ctx("tekrar"));

        assertThat(store.history(btc, "supply"))
                .as("aynı değer yeniden yazılmamalı")
                .hasSize(1);
    }

    @Test
    @DisplayName("şema kısıtları yazmadan önce doğrulanır")
    void schemaConstraintsAreEnforced() {
        assertThatThrownBy(() -> store.setProperty(btc, "supply", Values.text("çok"), T0, ctx("yanlış tip")))
                .isInstanceOf(OntologyException.SchemaViolation.class)
                .hasMessageContaining("DECIMAL");

        assertThatThrownBy(() -> store.setProperty(btc, "supply", Values.number("-5"), T0, ctx("min ihlali")))
                .isInstanceOf(OntologyException.SchemaViolation.class)
                .hasMessageContaining("küçük olamaz");

        assertThatThrownBy(() -> store.setProperty(btc, "category", Values.text("MEME"), T0, ctx("enum")))
                .isInstanceOf(OntologyException.SchemaViolation.class)
                .hasMessageContaining("izin verilenler");

        assertThatThrownBy(() -> store.setProperty(btc, "yokBöyleAlan", Values.text("x"), T0, ctx("yok")))
                .isInstanceOf(OntologyException.NotFound.class);
    }

    @Test
    @DisplayName("LIST alanı sıralı yazılır ve tümü birlikte değişir")
    void listCardinalityRoundTrips() {
        clock.setTo(Instant.parse("2026-02-01T00:00:00Z"));
        Instant feb = Instant.parse("2026-02-01T00:00:00Z");
        store.setPropertyList(btc, "tags", List.of(Values.text("L1"), Values.text("PoW")), feb, ctx("etiketler"));

        assertThat(store.current(btc).orElseThrow().getList("tags"))
                .containsExactly("L1", "PoW");

        clock.setTo(Instant.parse("2026-04-01T00:00:00Z"));
        Instant apr = Instant.parse("2026-04-01T00:00:00Z");
        store.setPropertyList(btc, "tags",
                List.of(Values.text("L1"), Values.text("PoW"), Values.text("STORE_OF_VALUE")), apr, ctx("ek etiket"));

        assertThat(store.current(btc).orElseThrow().getList("tags"))
                .containsExactly("L1", "PoW", "STORE_OF_VALUE");
        assertThat(store.asOf(btc, Instant.parse("2026-03-01T00:00:00Z")).orElseThrow().getList("tags"))
                .as("şubat-nisan arasında iki etiket vardı")
                .containsExactly("L1", "PoW");

        // Aynı liste tekrar yazılırsa geçmişe dokunulmamalı
        int before = store.history(btc, "tags").size();
        clock.setTo(Instant.parse("2026-05-01T00:00:00Z"));
        store.setPropertyList(btc, "tags",
                List.of(Values.text("L1"), Values.text("PoW"), Values.text("STORE_OF_VALUE")),
                Instant.parse("2026-05-01T00:00:00Z"), ctx("değişmedi"));
        assertThat(store.history(btc, "tags")).hasSize(before);
    }

    @Test
    @DisplayName("SINGLE ve LIST alanlar birbirinin metodunu kabul etmez")
    void cardinalityMismatchIsRejected() {
        assertThatThrownBy(() -> store.setPropertyList(btc, "supply", List.of(Values.number(1)), T0, ctx("yanlış")))
                .isInstanceOf(OntologyException.SchemaViolation.class)
                .hasMessageContaining("SINGLE");

        assertThatThrownBy(() -> store.setProperty(btc, "tags", Values.text("L1"), T0, ctx("yanlış")))
                .isInstanceOf(OntologyException.SchemaViolation.class)
                .hasMessageContaining("LIST");
    }

    @Test
    @DisplayName("aynı external_id ile ikinci nesne oluşturulamaz")
    void duplicateExternalIdIsRejected() {
        assertThatThrownBy(() -> store.createObject(TYPE, "BINANCE:BTC", ctx("tekrar")))
                .isInstanceOf(OntologyException.DuplicateObject.class);

        assertThat(store.findOrCreate(TYPE, "BINANCE:BTC", ctx("bul veya oluştur")).id())
                .as("findOrCreate mevcut nesneyi döndürmeli")
                .isEqualTo(btc.id());
    }

    @Test
    @DisplayName("veri varken zorunlu alan doğrudan eklenemez")
    void requiredPropertyCannotBeAddedToPopulatedType() {
        assertThatThrownBy(() -> store.defineProperty(TYPE,
                NewPropertyType.of("website", "Site", DataType.STRING).required(), ctx("zorunlu alan")))
                .isInstanceOf(OntologyException.SchemaViolation.class)
                .hasMessageContaining("zorunlu alan doğrudan eklenemez");
    }

    @Test
    @DisplayName("soyut tipten nesne oluşturulamaz")
    void abstractTypeCannotBeInstantiated() {
        store.defineObjectType(new NewObjectType("Asset", "Varlık", null, null, true, null), ctx("soyut"));
        assertThatThrownBy(() -> store.createObject("Asset", "X:1", ctx("olmaz")))
                .isInstanceOf(OntologyException.SchemaViolation.class)
                .hasMessageContaining("soyut");
    }

    @Test
    @DisplayName("her yazma denetim defterine düşer ve defter değiştirilemez")
    void changeLogIsAppendOnly() {
        clock.setTo(Instant.parse("2026-02-01T00:00:00Z"));
        store.setProperty(btc, "supply", Values.number("19500000"), Instant.parse("2026-02-01T00:00:00Z"),
                ctx("arz"));

        Long entries = jdbc.sql("SELECT count(*) FROM ontology_change_log WHERE object_id = :id")
                .param("id", btc.id()).query(Long.class).single();
        assertThat(entries).as("nesne oluşturma ve alan yazma kayıtlı").isGreaterThanOrEqualTo(2);

        assertThatThrownBy(() -> jdbc.sql("UPDATE ontology_change_log SET op = 'ALTER_TYPE'").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ontology_change_log").update())
                .hasMessageContaining("append-only");
    }
}
