package com.investor.llm.internal;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM katmanı ayarları.
 *
 * <p>Varsayılanlar LangChain4j'in demo uç noktasını gösteriyor: anahtar almadan, hesap
 * açmadan hat uçtan uca çalışsın diye. Demo uç noktası hız sınırlı ve garantisiz — üretimde
 * {@code base-url} ve {@code api-key} kendi anahtarınızla ezilmeli.
 *
 * <p><b>Anahtar yönetimi:</b> {@code api-key} koda, {@code application.yml}'ye ya da git'e
 * yazılmaz; ortam değişkeninden okunur ve üretimde AWS Secrets Manager'dan gelir. Bu alan
 * hiçbir log satırında ve hiçbir hata mesajında görünmemeli.
 *
 * @param enabled            kapalıyken LLM istemcisi hiç oluşturulmaz ve kural tabanlı
 *                           yedekler yerinde kalır
 * @param strictSchema       uç nokta {@code json_schema} yanıt biçimini destekliyor mu.
 *                           Demo uç noktasında kapalı: desteklemeyen bir uç noktaya katı
 *                           şema göndermek çağrının tamamını hataya düşürür; şema o durumda
 *                           isteme metin olarak gömülüyor.
 * @param monthlyBudgetUsd   aylık harcama tavanı; aşılınca çağrılar reddedilir
 */
@ConfigurationProperties(prefix = "investor.llm")
public record LlmProperties(
        Boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        Boolean strictSchema,
        Duration timeout,
        Integer maxRetries,
        BigDecimal monthlyBudgetUsd,
        Pricing pricing) {

    /** LangChain4j'in anahtar gerektirmeyen demo uç noktası. */
    public static final String DEMO_BASE_URL = "http://langchain4j.dev/demo/openai/v1";
    public static final String DEMO_API_KEY = "demo";
    public static final String DEMO_MODEL = "gpt-4o-mini";

    public LlmProperties {
        enabled = enabled == null || enabled;
        baseUrl = blankToNull(baseUrl) == null ? DEMO_BASE_URL : baseUrl.trim();
        apiKey = blankToNull(apiKey) == null ? DEMO_API_KEY : apiKey.trim();
        model = blankToNull(model) == null ? DEMO_MODEL : model.trim();
        // Demo uç noktası bir vekil; arkasındaki modelin katı şemayı desteklediğine dair
        // bir güvence yok. Kendi anahtarına geçen kullanıcı bunu açar.
        strictSchema = strictSchema != null && strictSchema;
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        maxRetries = maxRetries == null ? 2 : maxRetries;
        monthlyBudgetUsd = monthlyBudgetUsd == null ? new BigDecimal("50") : monthlyBudgetUsd;
        pricing = pricing == null ? Pricing.defaults() : pricing;

        if (monthlyBudgetUsd.signum() <= 0) {
            throw new IllegalArgumentException("aylık LLM bütçesi pozitif olmalı");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("yeniden deneme sayısı negatif olamaz");
        }
    }

    /** Demo uç noktasında mıyız — açılış logunda uyarmak için. */
    public boolean usingDemoEndpoint() {
        return DEMO_BASE_URL.equals(baseUrl) || DEMO_API_KEY.equals(apiKey);
    }

    /**
     * Milyon token başına fiyatlar (USD).
     *
     * <p>Sağlayıcı fiyat listesinden elle giriliyor. Yanlış girilirse bütçe tavanı yanlış
     * yerde durur — bu yüzden fiyatlar ayarda görünür tutuluyor, koda gömülmüyor.
     */
    public record Pricing(BigDecimal inputPerMillion, BigDecimal cachedInputPerMillion,
                          BigDecimal outputPerMillion) {

        public Pricing {
            inputPerMillion = inputPerMillion == null ? new BigDecimal("0.15") : inputPerMillion;
            cachedInputPerMillion =
                    cachedInputPerMillion == null ? new BigDecimal("0.075") : cachedInputPerMillion;
            outputPerMillion = outputPerMillion == null ? new BigDecimal("0.60") : outputPerMillion;
        }

        /** gpt-4o-mini fiyatları (Ağustos 2026). Model değişirse burası da değişmeli. */
        public static Pricing defaults() {
            return new Pricing(null, null, null);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
