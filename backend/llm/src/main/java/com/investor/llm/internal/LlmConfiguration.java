package com.investor.llm.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;

import com.investor.llm.LlmClient;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.json.JsonMapper;

/**
 * LLM katmanı bean tanımları.
 *
 * <p>{@code investor.llm.enabled=false} olduğunda hiçbir bean oluşmaz ve {@link LlmClient}
 * arayan modüller kendi kural tabanlı yedeklerine düşer. Bu bilinçli: sistemin LLM olmadan
 * da uçtan uca koşabilmesi, model kalitesini hattın doğruluğundan ayrı test edebilmenin
 * tek yolu.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmProperties.class)
@ConditionalOnProperty(name = "investor.llm.enabled", havingValue = "true", matchIfMissing = true)
public class LlmConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmConfiguration.class);

    /**
     * JSON eşleyici bean değil, modül içi bir sabit.
     *
     * <p>Aynı gerekçe ontoloji modülündeki gibi: model cevaplarının ayrıştırılması,
     * uygulamanın sunum katmanı ayarlarına (tarih biçimi, null davranışı) bağlı olmamalı.
     * Biri REST çıktısını güzelleştirmek için bir ayar değiştirdiğinde çıkarım hattının
     * davranışı değişmesin. Bean yapılsaydı Boot'un otomatik yapılandırdığı eşleyiciyle
     * belirsizlik doğardı.
     */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    LlmCallLog llmCallLog(JdbcClient jdbcClient, Clock clock, LlmProperties properties) {
        LlmProperties.Pricing pricing = properties.pricing();
        return new LlmCallLog(jdbcClient, JSON, clock, pricing.inputPerMillion(),
                pricing.cachedInputPerMillion(), pricing.outputPerMillion());
    }

    /**
     * Bütçe sayacı açılışta veritabanından geri yükleniyor. Yüklenemezse sayaç sıfırdan
     * başlar ve bu <em>uyarı</em> ile bildirilir: tavan o ay için gerçekte olduğundan
     * yüksek durur, sessizce geçilecek bir durum değil.
     */
    @Bean
    LlmBudget llmBudget(LlmProperties properties, LlmCallLog callLog, Clock clock) {
        LlmProperties.Pricing pricing = properties.pricing();
        LlmBudget budget = new LlmBudget(properties.monthlyBudgetUsd(), pricing.inputPerMillion(),
                pricing.cachedInputPerMillion(), pricing.outputPerMillion(), clock);
        try {
            Map<String, BigDecimal> spent = callLog.spendThisMonth();
            spent.forEach(budget::seed);
            if (!spent.isEmpty()) {
                log.info("LLM butcesi geri yuklendi: bu ay {} USD harcanmis (tavan {} USD)",
                        budget.totalSpend(), properties.monthlyBudgetUsd());
            }
        } catch (RuntimeException e) {
            log.warn("LLM butce sayaci gecmis harcamayla yuklenemedi; sayac sifirdan basliyor "
                    + "ve tavan bu ay icin oldugundan yuksek duruyor", e);
        }
        return budget;
    }

    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    ChatModel chatModel(LlmProperties properties) {
        if (properties.usingDemoEndpoint()) {
            log.warn("LLM demo ucu kullaniliyor ({}). Hiz sinirli ve garantisiz; uretimde "
                            + "investor.llm.base-url ve investor.llm.api-key ezilmeli.",
                    properties.baseUrl());
        }
        return OpenAiChatModel.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .modelName(properties.model())
                .timeout(properties.timeout())
                .maxRetries(properties.maxRetries())
                // logRequests/logResponses kapalı: istem, düşman haber metni içeriyor ve
                // cevap gövdesi loglara düşerse hem gürültü hem de istenmeyen bir kopya olur.
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    LlmClient llmClient(ChatModel chatModel, LlmProperties properties, LlmBudget budget,
                        LlmCallLog callLog, Clock clock) {
        return new LangChain4jLlmClient(chatModel, properties.model(), properties.strictSchema(),
                budget, callLog, () -> new ResponseValidator(JSON), clock);
    }
}
