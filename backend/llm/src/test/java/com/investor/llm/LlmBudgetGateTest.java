package com.investor.llm;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.investor.ontology.support.PostgresResource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bütçe tavanının hattı gerçekten durdurduğunu doğrular.
 *
 * <p>Ayrı bir test sınıfı, çünkü tavanın düşük olması gerekiyor ve bu bütün bağlamı
 * etkiliyor. Doğrulanan şey birim testin kapsamadığı kısım: tavan dolduğunda dışarıya
 * <em>hiç istek gitmiyor</em>. Para kontrol edildikten sonra harcansaydı tavan, harcamayı
 * ölçen ama engellemeyen bir sayaç olurdu.
 */
@SpringBootTest(classes = LlmTestApplication.class)
class LlmBudgetGateTest {

    private static WireMockServer openAi;

    @Autowired
    LlmClient llm;

    @Autowired
    JdbcClient jdbc;

    private static final OutputSchema SCHEMA = OutputSchema.named("test")
            .number("skor", 0, 1, "skor").requiredField()
            .build();

    @BeforeAll
    static void startStub() {
        openAi = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAi.start();
        openAi.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id": "c", "object": "chat.completion", "created": 1787000000,
                                 "model": "gpt-4o-mini",
                                 "choices": [{"index": 0, "finish_reason": "stop",
                                   "message": {"role": "assistant", "content": "{\\"skor\\": 0.5}"}}],
                                 "usage": {"prompt_tokens": 500000, "completion_tokens": 0,
                                           "total_tokens": 500000}}
                                """)));
    }

    @AfterAll
    static void stopStub() {
        openAi.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresResource db = PostgresResource.get();
        registry.add("spring.datasource.url", db::url);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("investor.llm.base-url", () -> openAi.baseUrl() + "/v1");
        registry.add("investor.llm.api-key", () -> "test-key");
        registry.add("investor.llm.max-retries", () -> "0");
        // Her çağrı 500k girdi tüketiyor, girdi 1 USD/M → çağrı başına 0.50 USD.
        registry.add("investor.llm.pricing.input-per-million", () -> "1.00");
        registry.add("investor.llm.pricing.cached-input-per-million", () -> "1.00");
        registry.add("investor.llm.pricing.output-per-million", () -> "1.00");
        registry.add("investor.llm.monthly-budget-usd", () -> "0.75");
    }

    @Test
    @DisplayName("Tavan dolduğunda dışarıya hiç istek gitmez")
    void refusesWithoutSendingRequest() {
        clearCallLog();
        openAi.resetRequests();

        // İlk çağrı geçer ve 0.50 USD harcar; tavan 0.75.
        llm.complete(call());
        assertThat(openAi.getAllServeEvents()).hasSize(1);

        // İkinci çağrı reddedilmeli: harcanan (0.50) tavanın altında ama ikinci çağrı
        // sonrası aşılacak. Kontrol harcamadan önce, bu yüzden ikinci çağrı geçiyor...
        llm.complete(call());
        assertThat(openAi.getAllServeEvents()).hasSize(2);

        // ...üçüncüsü geçmiyor: 1.00 > 0.75.
        assertThatThrownBy(() -> llm.complete(call()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("bütçesi doldu");

        // Asıl iddia: reddedilen çağrı için ağa çıkılmadı.
        assertThat(openAi.getAllServeEvents())
                .as("bütçe dolduktan sonra istek gönderilmemeli")
                .hasSize(2);

        // Reddedilen çağrı llm_call'a da yazılmıyor — para harcanmadı, kaydedilecek bir şey yok.
        Integer rows = jdbc.sql("SELECT count(*) FROM llm_call").query(Integer.class).single();
        assertThat(rows).isEqualTo(2);
    }

    /**
     * Kayıt tablosu salt-ekleme; sıfırlamak için trigger'ı geçici kapatıyoruz. Bu, testin
     * bir ayrıcalığı — üretimde kimse bunu yapmıyor ve yapamamalı.
     */
    private void clearCallLog() {
        jdbc.sql("ALTER TABLE llm_call DISABLE TRIGGER USER").update();
        try {
            jdbc.sql("DELETE FROM llm_call").update();
        } finally {
            jdbc.sql("ALTER TABLE llm_call ENABLE TRIGGER USER").update();
        }
    }

    private static LlmCall call() {
        return LlmCall.forPurpose("test").instruction("skorla").schema(SCHEMA).build();
    }
}
