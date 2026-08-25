package com.investor.knowledge;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.investor.knowledge.model.MacroPoint;
import com.investor.knowledge.model.MacroSeriesSpec;
import com.investor.knowledge.support.AbstractKnowledgeTest;
import com.investor.ontology.model.ObjectRef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faz-2 kapı testi: makro revizyonlar.
 *
 * <p>İddia: bir makro rakam düzeltildiğinde geçmiş bozulmuyor. "15 Ağustos'ta hangi CPI
 * rakamını görüyorduk" sorusu, rakam 15 Eylül'de düzeltildikten sonra da doğru
 * cevaplanıyor.
 *
 * <p>Neden kapı: bugün bildiğimiz düzeltilmiş rakamla geçmişi yeniden oynatmak,
 * backtest'i sistematik olarak iyimser gösterir. Karar anında elimizde olmayan bir
 * bilgiyle karar vermiş oluruz ve sonuç, gerçekte olduğundan iyi görünür.
 */
@DisplayName("Kapı testi: makro revizyonlar")
@Import(MacroRevisionGateTest.StubMacroConfiguration.class)
class MacroRevisionGateTest extends AbstractKnowledgeTest {

    private static final LocalDate JULY = LocalDate.of(2026, 7, 1);
    private static final Instant FIRST_RELEASE = Instant.parse("2026-08-15T00:00:00Z");
    private static final Instant REVISION = Instant.parse("2026-09-15T00:00:00Z");

    /** Vintage'ları elle kontrol edebilmek için borsa yerine geçen kaynak. */
    static class StubMacroSource implements MacroSource {
        final List<MacroPoint> points = new ArrayList<>();

        @Override
        public String sourceName() {
            return "FRED";
        }

        @Override
        public Optional<MacroSeriesSpec> describe(String seriesCode) {
            return Optional.of(new MacroSeriesSpec(seriesCode, "Tüketici Fiyat Endeksi",
                    "Index 1982-1984=100", "M"));
        }

        @Override
        public List<MacroPoint> observations(String seriesCode, LocalDate since) {
            return List.copyOf(points);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubMacroConfiguration {
        @Bean
        @Primary
        StubMacroSource stubMacroSource() {
            return new StubMacroSource();
        }
    }

    @Autowired
    private MacroIngest macroIngest;

    @Autowired
    private StubMacroSource source;

    /**
     * Stub bean'i Spring bağlamıyla birlikte yaşıyor ve testler arasında paylaşılıyor;
     * temizlenmezse bir testin verisi diğerine sızar.
     */
    @org.junit.jupiter.api.BeforeEach
    void clearStub() {
        source.points.clear();
    }

    @Test
    @DisplayName("revizyon eskisini ezmez; her tarih kendi rakamını görür")
    void revisionDoesNotOverwriteHistory() {
        // İlk yayın: 15 Ağustos'ta 314.2 açıklanıyor, 15 Eylül'e kadar resmî rakam bu.
        source.points.add(new MacroPoint("CPIAUCSL", JULY, new BigDecimal("314.2"),
                FIRST_RELEASE, REVISION, false));
        // Düzeltme: 15 Eylül'den itibaren 314.5.
        source.points.add(new MacroPoint("CPIAUCSL", JULY, new BigDecimal("314.5"),
                REVISION, null, true));

        clock.setTo(Instant.parse("2026-10-01T00:00:00Z"));
        int written = macroIngest.syncSeries("CPIAUCSL", null);
        assertThat(written).isEqualTo(2);

        ObjectRef observation = ontology
                .findByExternalId("MacroObservation", "FRED:CPIAUCSL:2026-07-01")
                .orElseThrow();

        assertThat(valueAsOf(observation, "2026-09-01T00:00:00Z"))
                .as("Eylül başında hâlâ ilk yayınlanan rakamı görüyorduk")
                .isEqualByComparingTo("314.2");

        assertThat(valueAsOf(observation, "2026-09-20T00:00:00Z"))
                .as("düzeltmeden sonra yeni rakam")
                .isEqualByComparingTo("314.5");

        assertThat(ontology.current(observation).orElseThrow().getNumber("value").orElseThrow())
                .as("bugünkü en iyi bilgimiz düzeltilmiş rakam")
                .isEqualByComparingTo("314.5");

        assertThat(ontology.history(observation, "value"))
                .as("iki sürüm de duruyor")
                .hasSize(2);
    }

    @Test
    @DisplayName("artımlı toplama: önce açık uçlu yazılan sürüm, revizyon gelince kapanır")
    void incrementalSyncClosesTheEarlierVintage() {
        // 1. tur: Ağustos'ta yalnızca ilk yayın var ve hâlâ geçerli.
        source.points.add(new MacroPoint("CPIAUCSL", JULY, new BigDecimal("314.2"),
                FIRST_RELEASE, null, false));
        clock.setTo(Instant.parse("2026-08-20T00:00:00Z"));
        assertThat(macroIngest.syncSeries("CPIAUCSL", null)).isEqualTo(1);

        ObjectRef observation = ontology
                .findByExternalId("MacroObservation", "FRED:CPIAUCSL:2026-07-01").orElseThrow();
        assertThat(ontology.current(observation).orElseThrow().getNumber("value").orElseThrow())
                .isEqualByComparingTo("314.2");

        // 2. tur: Eylül'de kaynak artık ilk yayını kapanmış, revizyonu açık bildiriyor.
        source.points.clear();
        source.points.add(new MacroPoint("CPIAUCSL", JULY, new BigDecimal("314.2"),
                FIRST_RELEASE, REVISION, false));
        source.points.add(new MacroPoint("CPIAUCSL", JULY, new BigDecimal("314.5"),
                REVISION, null, true));
        clock.setTo(Instant.parse("2026-09-20T00:00:00Z"));

        assertThat(macroIngest.syncSeries("CPIAUCSL", null))
                .as("ilk sürüm zaten kayıtlı, yalnızca revizyon yazılmalı")
                .isEqualTo(1);

        assertThat(valueAsOf(observation, "2026-09-01T00:00:00Z"))
                .as("açık uçlu yazılan ilk sürüm, revizyonun başlangıcında kapanmış olmalı")
                .isEqualByComparingTo("314.2");
        assertThat(valueAsOf(observation, "2026-10-01T00:00:00Z"))
                .isEqualByComparingTo("314.5");
        assertThat(ontology.history(observation, "value")).hasSize(2);
    }

    @Test
    @DisplayName("aynı seri tekrar çekilince yeni kayıt oluşmaz")
    void resyncIsIdempotent() {
        source.points.add(new MacroPoint("CPIAUCSL", JULY, new BigDecimal("314.2"),
                FIRST_RELEASE, REVISION, false));
        source.points.add(new MacroPoint("CPIAUCSL", JULY, new BigDecimal("314.5"),
                REVISION, null, true));
        clock.setTo(Instant.parse("2026-10-01T00:00:00Z"));

        assertThat(macroIngest.syncSeries("CPIAUCSL", null)).isEqualTo(2);
        assertThat(macroIngest.syncSeries("CPIAUCSL", null))
                .as("ikinci tur hiçbir şey yazmamalı")
                .isZero();

        ObjectRef observation = ontology
                .findByExternalId("MacroObservation", "FRED:CPIAUCSL:2026-07-01").orElseThrow();
        assertThat(ontology.history(observation, "value")).hasSize(2);
    }

    @Test
    @DisplayName("gözlem göstergeye bağlanır ve gösterge tanımı yazılır")
    void observationIsLinkedToIndicator() {
        source.points.add(new MacroPoint("CPIAUCSL", JULY, new BigDecimal("314.2"),
                FIRST_RELEASE, null, false));
        clock.setTo(Instant.parse("2026-08-20T00:00:00Z"));
        macroIngest.syncSeries("CPIAUCSL", null);

        ObjectRef observation = ontology
                .findByExternalId("MacroObservation", "FRED:CPIAUCSL:2026-07-01").orElseThrow();
        assertThat(ontology.current(observation).orElseThrow().linked("OF"))
                .singleElement()
                .satisfies(link -> assertThat(link.targetExternalId()).isEqualTo("FRED:CPIAUCSL"));

        ObjectRef indicator = ontology
                .findByExternalId("MacroIndicator", "FRED:CPIAUCSL").orElseThrow();
        assertThat(ontology.current(indicator).orElseThrow().getText("displayName"))
                .contains("Tüketici Fiyat Endeksi");
    }

    // ------------------------------------------------------------------

    private BigDecimal valueAsOf(ObjectRef observation, String isoInstant) {
        return ontology.asOf(observation, Instant.parse(isoInstant))
                .orElseThrow(() -> new AssertionError("gözlem " + isoInstant + " anında yok"))
                .getNumber("value")
                .orElseThrow(() -> new AssertionError("değer " + isoInstant + " anında yok"));
    }
}
