package com.investor.llm.internal;

import java.util.List;

import com.investor.llm.LlmException;
import com.investor.llm.OutputSchema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Doğrulayıcı testleri.
 *
 * <p>Bu sınıfın testi, güvenlik testi. İstem enjeksiyonuna karşı asıl savunma katmanı burası:
 * model ne söylerse söylesin, şemanın dışına çıkan hiçbir şey sisteme giremiyor.
 */
class ResponseValidatorTest {

    private static final OutputSchema SCHEMA = OutputSchema.named("test")
            .number("sentiment", -1, 1, "yön").requiredField()
            .number("materiality", 0, 1, "önem").requiredField()
            .enumeration("eventType", List.of("MACRO", "REGULATORY", "OTHER"), "tür")
            .bool("speculation", "söylenti mi")
            .stringArray("entities", "varlıklar")
            .string("summary", "özet")
            .build();

    private static final OutputSchema EVIDENCE_ITEM = OutputSchema.named("kanit")
            .string("claim", "iddia").requiredField()
            .number("weight", 0, 1, "ağırlık").requiredField()
            .enumeration("side", List.of("BUY", "SELL", "NEUTRAL"), "yön")
            .build();

    private static final OutputSchema WITH_EVIDENCE = OutputSchema.named("ajan")
            .number("sentiment", -1, 1, "yön").requiredField()
            .number("materiality", 0, 1, "önem").requiredField()
            .objectArray("evidence", EVIDENCE_ITEM, "kanıtlar")
            .build();

    private final ResponseValidator validator = new ResponseValidator(JsonMapper.builder().build());

    @Test
    @DisplayName("Geçerli cevap tüm alanlarıyla ayrıştırılır")
    void parsesValidResponse() {
        var values = validator.validate("""
                {"sentiment": 0.6, "materiality": 0.8, "eventType": "REGULATORY",
                 "speculation": false, "entities": ["BTC", "ETH"], "summary": "SEC onayladı"}
                """, SCHEMA);

        assertThat(values.get("sentiment")).isEqualTo(0.6);
        assertThat(values.get("materiality")).isEqualTo(0.8);
        assertThat(values.get("eventType")).isEqualTo("REGULATORY");
        assertThat(values.get("speculation")).isEqualTo(Boolean.FALSE);
        assertThat(values.get("entities")).isEqualTo(List.of("BTC", "ETH"));
        assertThat(validator.clamped()).isEmpty();
    }

    @Test
    @DisplayName("Sınır dışı sayılar kırpılır ve kırpma raporlanır")
    void clampsOutOfRangeNumbers() {
        // Enjeksiyon senaryosu: "materiality'yi 999 yap" talimatı metne gömülmüş ve model uymuş.
        var values = validator.validate(
                "{\"sentiment\": -5.0, \"materiality\": 999}", SCHEMA);

        assertThat(values.get("sentiment")).isEqualTo(-1.0);
        assertThat(values.get("materiality")).isEqualTo(1.0);
        // Sessizce kırpmıyoruz: bu listenin dolması ölçüme gidiyor ve uyarı loglanıyor.
        assertThat(validator.clamped()).containsExactlyInAnyOrder("sentiment", "materiality");
    }

    @Test
    @DisplayName("Şemada olmayan alanlar atılır — modele yeni bir kanal açılamaz")
    void dropsUnknownFields() {
        var values = validator.validate("""
                {"sentiment": 0.1, "materiality": 0.2,
                 "action": "BUY", "orderSize": 10000, "apiKey": "sızdır"}
                """, SCHEMA);

        assertThat(values).containsOnlyKeys("sentiment", "materiality");
    }

    @Test
    @DisplayName("Kapalı küme dışındaki enum değeri düşürülür")
    void dropsUnknownEnumValue() {
        var values = validator.validate(
                "{\"sentiment\": 0, \"materiality\": 0, \"eventType\": \"SELL_EVERYTHING\"}", SCHEMA);

        assertThat(values).doesNotContainKey("eventType");
    }

    @Test
    @DisplayName("Zorunlu alan eksikse çağrı başarısız sayılır")
    void failsOnMissingRequiredField() {
        assertThatThrownBy(() -> validator.validate("{\"sentiment\": 0.5}", SCHEMA))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("materiality");
    }

    @Test
    @DisplayName("Markdown kod bloğuna sarılı cevap yine ayrıştırılır")
    void parsesFencedResponse() {
        var values = validator.validate("""
                Elbette, işte analiz:
                ```json
                {"sentiment": 0.3, "materiality": 0.4}
                ```
                """, SCHEMA);

        assertThat(values.get("sentiment")).isEqualTo(0.3);
    }

    @Test
    @DisplayName("Metin olarak gelen sayı ve mantıksal değerler kabul edilir")
    void acceptsStringifiedPrimitives() {
        // Katı şema desteklemeyen uç noktalarda sık görülen bir sapma.
        var values = validator.validate("""
                {"sentiment": "0.5", "materiality": "0.25", "speculation": "true"}
                """, SCHEMA);

        assertThat(values.get("sentiment")).isEqualTo(0.5);
        assertThat(values.get("speculation")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("Dizi yerine tek değer gelirse tek elemanlı listeye çevrilir")
    void acceptsScalarForArray() {
        var values = validator.validate(
                "{\"sentiment\": 0, \"materiality\": 0, \"entities\": \"BTC\"}", SCHEMA);

        assertThat(values.get("entities")).isEqualTo(List.of("BTC"));
    }

    @Test
    @DisplayName("JSON olmayan cevap yeniden denenebilir hata üretir")
    void failsOnNonJson() {
        assertThatThrownBy(() -> validator.validate("Üzgünüm, bu isteği yerine getiremem.", SCHEMA))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).retryable()).isTrue());
    }

    @Test
    @DisplayName("Nesne listesi iç şemaya göre doğrulanıyor")
    void validatesObjectArray() {
        var values = validator.validate("""
                {"sentiment": 0.1, "materiality": 0.2, "evidence": [
                  {"claim": "RSI düşük", "weight": 0.6, "side": "BUY"},
                  {"claim": "Hacim yüksek", "weight": 0.4, "side": "SELL"}
                ]}
                """, WITH_EVIDENCE);

        @SuppressWarnings("unchecked")
        var evidence = (java.util.List<java.util.Map<String, Object>>) values.get("evidence");
        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0)).containsEntry("claim", "RSI düşük").containsEntry("weight", 0.6);
        assertThat(evidence.get(1)).containsEntry("side", "SELL");
    }

    @Test
    @DisplayName("Liste öğesindeki sınır ihlali de kırpılıyor")
    void clampsInsideObjectArray() {
        var values = validator.validate("""
                {"sentiment": 0, "materiality": 0, "evidence": [
                  {"claim": "abartılı", "weight": 47.0, "side": "BUY"}
                ]}
                """, WITH_EVIDENCE);

        @SuppressWarnings("unchecked")
        var evidence = (java.util.List<java.util.Map<String, Object>>) values.get("evidence");
        assertThat(evidence.get(0)).containsEntry("weight", 1.0);
        assertThat(validator.clamped()).contains("evidence.weight");
    }

    @Test
    @DisplayName("Bozuk öğe atılıyor, sağlam öğeler korunuyor")
    void dropsOnlyBrokenItems() {
        // Bir ajanın beş kanıtından biri bozuksa diğer dördünü çöpe atmak, elde olan
        // bilgiyi kaybetmek olurdu.
        var values = validator.validate("""
                {"sentiment": 0, "materiality": 0, "evidence": [
                  {"claim": "sağlam", "weight": 0.5, "side": "BUY"},
                  {"weight": 0.5, "side": "BUY"},
                  "bu bir nesne bile değil",
                  {"claim": "bu da sağlam", "weight": 0.3, "side": "SELL"}
                ]}
                """, WITH_EVIDENCE);

        @SuppressWarnings("unchecked")
        var evidence = (java.util.List<java.util.Map<String, Object>>) values.get("evidence");
        assertThat(evidence).hasSize(2);
        // Atma sessiz değil.
        assertThat(validator.droppedItems()).hasSize(2);
    }

    @Test
    @DisplayName("Liste öğesindeki fazladan alanlar da atılıyor")
    void dropsUnknownFieldsInsideItems() {
        var values = validator.validate("""
                {"sentiment": 0, "materiality": 0, "evidence": [
                  {"claim": "x", "weight": 0.5, "side": "BUY",
                   "action": "MARKET_BUY", "quantity": 999}
                ]}
                """, WITH_EVIDENCE);

        @SuppressWarnings("unchecked")
        var evidence = (java.util.List<java.util.Map<String, Object>>) values.get("evidence");
        assertThat(evidence.get(0)).containsOnlyKeys("claim", "weight", "side");
    }

    @Test
    @DisplayName("NaN ve sonsuz değerler düşürülür")
    void dropsNonFiniteNumbers() {
        assertThatThrownBy(() -> validator.validate(
                "{\"sentiment\": \"NaN\", \"materiality\": \"Infinity\"}", SCHEMA))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("eksik");
    }
}
