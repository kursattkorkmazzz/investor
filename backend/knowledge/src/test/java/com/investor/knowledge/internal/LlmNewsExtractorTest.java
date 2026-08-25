package com.investor.knowledge.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.investor.knowledge.model.EventType;
import com.investor.knowledge.model.NewsAnalysis;
import com.investor.knowledge.model.RawNewsItem;
import com.investor.llm.LlmCall;
import com.investor.llm.LlmClient;
import com.investor.llm.LlmException;
import com.investor.llm.LlmResult;
import com.investor.llm.LlmUsage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM haber çıkarıcısı testleri.
 *
 * <p>Sahte bir {@link LlmClient} kullanıyor: buradaki soru modelin ne kadar iyi olduğu değil,
 * cevabının doğru yorumlandığı ve başarısızlığın dürüstçe raporlandığı.
 */
class LlmNewsExtractorTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final RawNewsItem ITEM = new RawNewsItem(
            "https://outlet.test/etf", "SEC approves spot Bitcoin ETF",
            "The commission cleared the first spot product.", null, NOW);

    private final HeuristicNewsExtractor heuristic =
            new HeuristicNewsExtractor(Map.of("bitcoin", "ASSET:BTC", "btc", "ASSET:BTC"));

    @Test
    @DisplayName("Model cevabı NewsAnalysis'e çevrilir")
    void mapsModelResponse() {
        var extractor = new LlmNewsExtractor(stub(Map.of(
                "sentiment", 0.8,
                "materiality", 0.9,
                "eventType", "REGULATORY",
                "timeHorizon", "WEEKS",
                "speculation", false,
                "entities", List.of("BTC"),
                "summary", "SEC ilk spot ürünü onayladı")), heuristic);

        NewsAnalysis analysis = extractor.analyze(ITEM);

        assertThat(analysis.sentiment()).isEqualTo(0.8);
        assertThat(analysis.materiality()).isEqualTo(0.9);
        assertThat(analysis.eventType()).isEqualTo(EventType.REGULATORY);
        assertThat(analysis.timeHorizon()).isEqualTo(NewsAnalysis.TimeHorizon.WEEKS);
        assertThat(analysis.entities()).containsExactly("ASSET:BTC");
        assertThat(analysis.summary()).isEqualTo("SEC ilk spot ürünü onayladı");
        assertThat(analysis.extractorId()).isEqualTo("llm:test-model");
    }

    @Test
    @DisplayName("Model erişilemezse yedeğe düşer ve sonuç YEDEĞİN kimliğiyle etiketlenir")
    void fallsBackHonestly() {
        LlmClient failing = new LlmClient() {
            @Override
            public LlmResult complete(LlmCall call) {
                throw new LlmException("model erişilemez", true);
            }

            @Override
            public String modelId() {
                return "test-model";
            }
        };

        NewsAnalysis analysis = new LlmNewsExtractor(failing, heuristic).analyze(ITEM);

        // Asıl iddia bu: ontolojiye "LLM çıkardı" yazılmıyor. Kimlik yalan söyleseydi
        // kalibrasyon iki çıkarıcının sonuçlarını karıştırır ve "LLM daha mı iyi" sorusu
        // sonsuza kadar cevapsız kalırdı.
        assertThat(analysis.extractorId()).isEqualTo("heuristic-v1");
    }

    @Test
    @DisplayName("Uydurulmuş semboller ontolojiye yazılmaz")
    void dropsInventedSymbols() {
        var extractor = new LlmNewsExtractor(stub(Map.of(
                "sentiment", 0.0,
                "materiality", 0.5,
                // "Bitcoin ETF onayı" gibi bir metin sembol değil; model yine de yazabiliyor.
                "entities", List.of("BTC", "asset:eth", "Bitcoin ETF onayı", "", "X"))), heuristic);

        assertThat(extractor.analyze(ITEM).entities())
                .containsExactly("ASSET:BTC", "ASSET:ETH");
    }

    @Test
    @DisplayName("Kapalı küme dışındaki olay türü OTHER'a düşer")
    void unknownEventTypeFallsBack() {
        var extractor = new LlmNewsExtractor(stub(Map.of(
                "sentiment", 0.0, "materiality", 0.5, "eventType", "SEND_ALL_FUNDS")), heuristic);

        assertThat(extractor.analyze(ITEM).eventType()).isEqualTo(EventType.OTHER);
    }

    @Test
    @DisplayName("Modele gövde değil başlık ve özet gidiyor, veri zarfın içinde")
    void sendsOnlyTitleAndSummaryAsUntrustedData() {
        AtomicReference<LlmCall> sent = new AtomicReference<>();
        var extractor = new LlmNewsExtractor(
                stub(Map.of("sentiment", 0.0, "materiality", 0.1), sent), heuristic);

        RawNewsItem withBody = new RawNewsItem(ITEM.url(), ITEM.title(), ITEM.summary(),
                "ÇOK UZUN GÖVDE".repeat(1000), NOW);
        extractor.analyze(withBody);

        LlmCall call = sent.get();
        assertThat(call.purpose()).isEqualTo("news-analysis");
        // Haber metni untrustedData'da: zarfa sarılacak olan alan bu.
        assertThat(call.untrustedData()).contains(ITEM.title()).doesNotContain("ÇOK UZUN GÖVDE");
        assertThat(call.instruction()).doesNotContain(ITEM.title());
        assertThat(call.metadata()).containsEntry("url", ITEM.url());
    }

    private LlmClient stub(Map<String, Object> values) {
        return stub(values, new AtomicReference<>());
    }

    private LlmClient stub(Map<String, Object> values, AtomicReference<LlmCall> captured) {
        return new LlmClient() {
            @Override
            public LlmResult complete(LlmCall call) {
                captured.set(call);
                return new LlmResult(UUID.randomUUID(), "test-model", values, "{}",
                        LlmUsage.NONE, Duration.ofMillis(10), false);
            }

            @Override
            public String modelId() {
                return "test-model";
            }
        };
    }
}
