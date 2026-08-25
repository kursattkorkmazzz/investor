package com.investor.llm.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.investor.llm.LlmCall;
import com.investor.llm.LlmClient;
import com.investor.llm.LlmException;
import com.investor.llm.LlmResult;
import com.investor.llm.LlmUsage;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LlmClient}'ın LangChain4j gerçeklemesi.
 *
 * <p>LangChain4j tipleri bu sınıfın ve {@link SchemaTranslator}'ın dışına çıkmaz. Sağlayıcı
 * değişirse (ya da LangChain4j'in kırıcı bir sürümü gelirse) değişecek yüzey bu ikisi.
 *
 * <p>Sırayla: bütçe kontrolü → çağrı → doğrulama → kayıt. Bütçe kontrolünün <em>önce</em>
 * olması önemli: para harcandıktan sonra "harcamamalıydık" demek işe yaramaz.
 */
class LangChain4jLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jLlmClient.class);

    private final ChatModel model;
    private final String modelId;
    private final boolean strictSchema;
    private final LlmBudget budget;
    private final LlmCallLog callLog;
    private final ResponseValidatorFactory validators;
    private final Clock clock;

    LangChain4jLlmClient(ChatModel model, String modelId, boolean strictSchema, LlmBudget budget,
                         LlmCallLog callLog, ResponseValidatorFactory validators, Clock clock) {
        this.model = model;
        this.modelId = modelId;
        this.strictSchema = strictSchema;
        this.budget = budget;
        this.callLog = callLog;
        this.validators = validators;
        this.clock = clock;
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public LlmResult complete(LlmCall call) {
        budget.checkAllowed(call.purpose());

        UUID callId = UUID.randomUUID();
        Instant started = clock.instant();
        ChatRequest request = buildRequest(call);

        ChatResponse response;
        try {
            response = model.chat(request);
        } catch (RuntimeException e) {
            Duration latency = Duration.between(started, clock.instant());
            callLog.recordFailure(callId, call, modelId, latency, e.toString());
            throw new LlmException("model çağrısı başarısız: " + call.purpose(), e, isRetryable(e));
        }

        Duration latency = Duration.between(started, clock.instant());
        LlmUsage usage = toUsage(response.tokenUsage());
        boolean truncated = response.finishReason() == FinishReason.LENGTH;
        String raw = response.aiMessage() == null ? null : response.aiMessage().text();

        // Bütçe, doğrulama başarısız olsa bile harcanan tokenı görmeli — para gitti.
        budget.record(call.purpose(), usage);

        Map<String, Object> values;
        ResponseValidator validator = validators.create();
        try {
            values = validator.validate(raw, call.schema());
        } catch (LlmException e) {
            callLog.record(callId, call, modelId, usage, latency, raw, List.of(), e.getMessage());
            if (truncated) {
                throw new LlmException(
                        "cevap token sınırında kesildi (maxOutputTokens=" + call.maxOutputTokens() + ")",
                        e, true);
            }
            throw e;
        }

        List<String> anomalies = new java.util.ArrayList<>(validator.clamped());
        validator.droppedItems().forEach(item -> anomalies.add("dropped:" + item));
        if (!anomalies.isEmpty()) {
            // Sessiz kalmıyoruz: şema ihlali ya model bozulmuş ya biri istemi zorluyor demek.
            log.warn("LLM cevabında şema ihlali düzeltildi: purpose={} alanlar={}",
                    call.purpose(), anomalies);
        }
        callLog.record(callId, call, modelId, usage, latency, raw, anomalies, null);

        return new LlmResult(callId, response.modelName() == null ? modelId : response.modelName(),
                values, raw, usage, latency, truncated);
    }

    private ChatRequest buildRequest(LlmCall call) {
        ChatRequest.Builder builder = ChatRequest.builder()
                .messages(
                        SystemMessage.from(PromptEnvelope.systemPrompt(call, strictSchema)),
                        UserMessage.from(PromptEnvelope.userMessage(call)))
                .maxOutputTokens(call.maxOutputTokens())
                // Sıfır sıcaklık: aynı kanıttan aynı çıkarımı istiyoruz. Yaratıcılık, kanıt
                // üretiminde istenen bir şey değil — geri test edilebilirliği bozar.
                .temperature(0.0);
        if (strictSchema) {
            builder.responseFormat(SchemaTranslator.toResponseFormat(call.schema()));
        }
        return builder.build();
    }

    private static LlmUsage toUsage(TokenUsage usage) {
        if (usage == null) {
            return LlmUsage.NONE;
        }
        int input = orZero(usage.inputTokenCount());
        int output = orZero(usage.outputTokenCount());
        int cached = 0;
        int reasoning = 0;
        if (usage instanceof OpenAiTokenUsage openAi) {
            if (openAi.inputTokensDetails() != null) {
                cached = orZero(openAi.inputTokensDetails().cachedTokens());
            }
            if (openAi.outputTokensDetails() != null) {
                reasoning = orZero(openAi.outputTokensDetails().reasoningTokens());
            }
        }
        return new LlmUsage(input, Math.min(cached, input), output, Math.min(reasoning, output));
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * Ağ ve hız sınırı hataları tekrar denenebilir; yetki ve şema hataları denenmemeli.
     * LangChain4j istisna hiyerarşisini sürümler arası değiştirdiği için tip yerine
     * mesaja bakıyoruz — kırılgan ama sürüme bağımlı olmayan taraf bu.
     */
    private static boolean isRetryable(RuntimeException e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (message.contains("401") || message.contains("403") || message.contains("unauthorized")
                || message.contains("invalid_api_key")) {
            return false;
        }
        return true;
    }

    /** Doğrulayıcı çağrı başına yeni üretilir: kırpma sayacı isteğe özel. */
    @FunctionalInterface
    interface ResponseValidatorFactory {
        ResponseValidator create();
    }
}
