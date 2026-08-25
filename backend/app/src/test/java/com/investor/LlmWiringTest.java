package com.investor;

import com.investor.knowledge.NewsExtractor;
import com.investor.llm.LlmClient;
import com.investor.ontology.support.PostgresResource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gerçek uygulama bağlamında LLM katmanının bağlanışı.
 *
 * <p>Modül testleri parçaların doğru çalıştığını gösteriyor; burada doğrulanan şey
 * <em>montaj</em>: LLM açıkken haber çıkarıcısının gerçekten LLM tabanlısı olduğu,
 * kapalıyken sistemin ayakta kaldığı. İkisi de gözden kaçması kolay hatalar —
 * uygulama her iki durumda da sorunsuz açılır, sadece yanlış şeyi yapar.
 */
class LlmWiringTest {

    static void datasource(DynamicPropertyRegistry registry) {
        PostgresResource db = PostgresResource.get();
        registry.add("spring.datasource.url", db::url);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
    }

    @Nested
    @SpringBootTest(properties = {
            "investor.llm.enabled=true",
            // Bağlam açılışında ağa çıkılmıyor: model nesnesi kuruluyor, çağrı yapılmıyor.
            "investor.llm.base-url=http://localhost:1/v1",
            "investor.llm.api-key=test-key"
    })
    @DisplayName("LLM açıkken")
    class Enabled {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            datasource(registry);
        }

        @Autowired
        NewsExtractor extractor;

        @Autowired
        LlmClient llm;

        @Autowired
        JdbcClient jdbc;

        @Test
        @DisplayName("haber çıkarıcısı LLM tabanlısına devrolur")
        void llmExtractorTakesOver() {
            // Kimliğin "llm:" ile başlaması, kural tabanlı yedeğin devre dışı kaldığını
            // gösteren tek doğrudan kanıt.
            assertThat(extractor.extractorId()).startsWith("llm:");
            assertThat(llm.modelId()).isNotBlank();
        }

        @Test
        @DisplayName("çağrı kaydı tablosu uygulama şemasında var")
        void callLogTableIsMigrated() {
            // V400 migration'ı uygulamanın Flyway konumlarına eklenmemiş olsaydı bütçe
            // sayacı sessizce sıfırdan başlardı ve tavan hiçbir şey ifade etmezdi.
            Integer count = jdbc.sql("SELECT count(*) FROM llm_call").query(Integer.class).single();
            assertThat(count).isZero();
        }
    }

    @Nested
    @SpringBootTest(properties = "investor.llm.enabled=false")
    @DisplayName("LLM kapalıyken")
    class Disabled {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            datasource(registry);
        }

        @Autowired
        NewsExtractor extractor;

        @Autowired
        org.springframework.context.ApplicationContext context;

        @Test
        @DisplayName("sistem kural tabanlı çıkarıcıyla ayakta kalır")
        void survivesWithoutLlm() {
            assertThat(context.getBeanNamesForType(LlmClient.class)).isEmpty();
            assertThat(extractor.extractorId()).isEqualTo("heuristic-v1");
        }
    }
}
