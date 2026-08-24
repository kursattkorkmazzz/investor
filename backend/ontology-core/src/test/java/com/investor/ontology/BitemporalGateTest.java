package com.investor.ontology;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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

/**
 * Faz-1 kapı testi.
 *
 * <p>Buradaki iddia şu: sistem, geçmişte <em>bildiği</em> ile bugün <em>bildiği</em>ni
 * ayırt edebiliyor. Bu ayrım olmadan backtest sistematik olarak gerçekte olduğundan iyi
 * görünür, kalibrasyon yanlış ölçer ve öğrenme yanlış dersi öğrenir — üstelik hiçbiri
 * hata olarak görünmez.
 *
 * <p>Bu test geçmeden üstüne bir şey inşa edilmez.
 */
@DisplayName("Kapı testi: bitemporal doğruluk")
class BitemporalGateTest extends AbstractOntologyTest {

    private static final String TYPE = "CryptoAsset";
    private static final String SUPPLY = "circulatingSupply";

    private ObjectRef btc;

    @BeforeEach
    void setUpOntology() {
        clock.setTo(T0);
        store.defineObjectType(NewObjectType.of(TYPE, "Kripto Varlık"), ctx("şema"));
        store.defineProperty(TYPE, NewPropertyType.of("name", "Ad", DataType.STRING).asTitle(), ctx("şema"));
        store.defineProperty(TYPE, NewPropertyType.of(SUPPLY, "Dolaşımdaki Arz", DataType.DECIMAL), ctx("şema"));

        btc = store.createObject(TYPE, "BINANCE:BTC", ctx("ilk kayıt"));
        store.setProperty(btc, "name", Values.text("Bitcoin"), T0, ctx("ilk kayıt"));
    }

    @Test
    @DisplayName("üç güncelleme + bir geri çekme sonrası her geçmiş an doğru cevaplanır")
    void bitemporalHistoryIsAnswerable() {
        Instant jan = Instant.parse("2026-01-01T00:00:00Z");
        Instant mar = Instant.parse("2026-03-01T00:00:00Z");
        Instant may = Instant.parse("2026-05-01T00:00:00Z");
        Instant jun = Instant.parse("2026-06-01T00:00:00Z");

        // --- 1. güncelleme: Ocak'ta 19.500.000 olduğunu öğreniyoruz
        clock.setTo(jan);
        store.setProperty(btc, SUPPLY, Values.number("19500000"), jan, ctx("ilk arz"));

        // --- 2. güncelleme: Mart'ta 19.650.000
        clock.setTo(mar);
        store.setProperty(btc, SUPPLY, Values.number("19650000"), mar, ctx("mart güncellemesi"));

        // --- 3. güncelleme: Mayıs'ta 19.800.000 (bu kayıt yanlış çıkacak)
        clock.setTo(may);
        store.setProperty(btc, SUPPLY, Values.number("19800000"), may, ctx("mayıs güncellemesi"));

        // --- Haziran: mayıs kaydının hatalı olduğu anlaşılıyor, geri çekiliyor ve düzeltiliyor
        clock.setTo(jun);
        store.retractProperty(btc, SUPPLY, ctx("kaynak yanlış rakam yayınlamış"));
        store.setProperty(btc, SUPPLY, Values.number("19780000"), may, ctx("düzeltilmiş mayıs rakamı"));

        // ------------------------------------------------------------------
        // "O gün ne biliyorduk?" — asıl iddia
        // ------------------------------------------------------------------

        assertThat(supplyAsOf("2026-02-01T00:00:00Z"))
                .as("Şubat'ta ocak rakamını biliyorduk")
                .isEqualByComparingTo("19500000");

        assertThat(supplyAsOf("2026-04-01T00:00:00Z"))
                .as("Nisan'da mart rakamını biliyorduk")
                .isEqualByComparingTo("19650000");

        assertThat(supplyAsOf("2026-05-15T00:00:00Z"))
                .as("15 Mayıs'ta HATALI rakamı biliyorduk — düzeltme henüz gelmemişti")
                .isEqualByComparingTo("19800000");

        assertThat(supplyAsOf("2026-06-15T00:00:00Z"))
                .as("Haziran'da düzeltilmiş rakamı biliyoruz")
                .isEqualByComparingTo("19780000");

        assertThat(currentSupply())
                .as("bugünkü en iyi bilgimiz düzeltilmiş rakam")
                .isEqualByComparingTo("19780000");

        // ------------------------------------------------------------------
        // Geçmiş kaybolmadı: dört kayıt da duruyor, biri geri çekilmiş olarak
        // ------------------------------------------------------------------

        List<PropertyHistoryEntry> history = store.history(btc, SUPPLY);
        assertThat(history).hasSize(4);
        assertThat(history).filteredOn(PropertyHistoryEntry::isRetracted)
                .as("yalnızca hatalı kayıt geri çekilmiş olmalı")
                .singleElement()
                .satisfies(entry -> assertThat((BigDecimal) entry.value()).isEqualByComparingTo("19800000"));
        assertThat(history).filteredOn(PropertyHistoryEntry::isOpen)
                .as("tam olarak bir açık kayıt olmalı")
                .singleElement()
                .satisfies(entry -> assertThat((BigDecimal) entry.value()).isEqualByComparingTo("19780000"));
    }

    @Test
    @DisplayName("sonradan öğrenilen bilgi, öğrenilmeden önceki sorgularda görünmez")
    void backdatedKnowledgeDoesNotLeakIntoThePast() {
        Instant valueValidFrom = Instant.parse("2026-02-01T00:00:00Z");
        Instant weLearnedIt = Instant.parse("2026-05-01T00:00:00Z");

        clock.setTo(weLearnedIt);
        store.setProperty(btc, SUPPLY, Values.number("19600000"), valueValidFrom,
                ctx("geriye dönük düzeltme: şubat rakamı"));

        // Bilgi 1 Mayıs'ta geldi. 1 Mart'ta bu rakamı bilmiyorduk — geçerlilik zamanı
        // şubata uzansa bile. Look-ahead bias'a karşı tek savunma bu.
        assertThat(store.asOf(btc, Instant.parse("2026-03-01T00:00:00Z"))
                .orElseThrow()
                .getNumber(SUPPLY))
                .as("mart ayında bu bilgiye sahip değildik")
                .isEmpty();

        assertThat(supplyAsOf("2026-06-01T00:00:00Z"))
                .as("haziranda ise biliyoruz ve şubattan beri geçerli sayıyoruz")
                .isEqualByComparingTo("19600000");
    }

    @Test
    @DisplayName("nesnenin var olması da zamansaldır")
    void objectExistenceIsTemporal() {
        Instant createdAt = Instant.parse("2026-04-01T00:00:00Z");
        clock.setTo(createdAt);
        ObjectRef eth = store.createObject(TYPE, "BINANCE:ETH", ctx("sonradan eklendi"));
        store.setProperty(eth, "name", Values.text("Ethereum"), createdAt, ctx("ad"));

        assertThat(store.asOf(eth, Instant.parse("2026-03-01T00:00:00Z")))
                .as("yaratılmadan önceki bir sorguda nesne yok")
                .isEmpty();
        assertThat(store.asOf(eth, Instant.parse("2026-05-01T00:00:00Z")))
                .as("yaratıldıktan sonra var")
                .isPresent();

        clock.setTo(Instant.parse("2026-07-01T00:00:00Z"));
        store.deleteObject(eth, ctx("listeden çıkarıldı"));

        assertThat(store.asOf(eth, Instant.parse("2026-06-01T00:00:00Z")))
                .as("silinmeden önceki sorguda hâlâ var — geçmiş değişmez")
                .isPresent();
        assertThat(store.asOf(eth, Instant.parse("2026-08-01T00:00:00Z")))
                .as("silindikten sonra yok")
                .isEmpty();
        assertThat(store.current(eth))
                .as("güncel projeksiyondan da düşmüş olmalı")
                .isEmpty();
    }

    @Test
    @DisplayName("kapatma ile geri çekme farklı şeylerdir")
    void closingDiffersFromRetracting() {
        Instant jan = Instant.parse("2026-01-01T00:00:00Z");
        clock.setTo(jan);
        store.setProperty(btc, SUPPLY, Values.number("19500000"), jan, ctx("ilk arz"));

        Instant apr = Instant.parse("2026-04-01T00:00:00Z");
        clock.setTo(apr);
        store.closeProperty(btc, SUPPLY, apr, ctx("artık geçerli değil"));

        // Kapatma: "bu değer bir dönem doğruydu, artık değil"
        assertThat(supplyAsOf("2026-03-01T00:00:00Z"))
                .as("kapatmadan önceki dönemde değer hâlâ görünür")
                .isEqualByComparingTo("19500000");
        assertThat(store.asOf(btc, Instant.parse("2026-05-01T00:00:00Z"))
                .orElseThrow().getNumber(SUPPLY))
                .as("kapatmadan sonra değer yok")
                .isEmpty();

        List<PropertyHistoryEntry> history = store.history(btc, SUPPLY);
        assertThat(history).singleElement().satisfies(entry -> {
            assertThat(entry.isRetracted()).as("kapatma geri çekme değildir").isFalse();
            assertThat(entry.validTo()).isEqualTo(apr);
        });
    }

    // ------------------------------------------------------------------

    private BigDecimal supplyAsOf(String isoInstant) {
        return store.asOf(btc, Instant.parse(isoInstant))
                .orElseThrow(() -> new AssertionError("nesne " + isoInstant + " anında bulunamadı"))
                .getNumber(SUPPLY)
                .orElseThrow(() -> new AssertionError(SUPPLY + " alanı " + isoInstant + " anında yok"));
    }

    private BigDecimal currentSupply() {
        return store.current(btc).orElseThrow().getNumber(SUPPLY).orElseThrow();
    }
}
