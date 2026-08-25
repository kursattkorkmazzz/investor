package com.investor.llm.internal;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.investor.llm.LlmCall;
import com.investor.llm.LlmUsage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.json.JsonMapper;

/**
 * Her LLM çağrısını {@code llm_call} tablosuna yazar.
 *
 * <p>Kayıt yazımı <em>çağrıyı bozmaz</em>: veritabanı yazımı başarısız olursa loglanır ve
 * geçilir. Tersi tercih edilebilirdi (kayıt yoksa çağrı da olmasın) ama o zaman geçici bir
 * veritabanı sorunu tüm analiz hattını durdururdu; kayıt kaybı kabul edilebilir, hattın
 * durması değil. Kayıp gözden kaçmasın diye {@code error} seviyesinde loglanıyor.
 */
class LlmCallLog {

    private static final Logger log = LoggerFactory.getLogger(LlmCallLog.class);

    /** Ham cevap için üst sınır: denetim için yeterli, kaçak bir cevabın tabloyu şişirmesine karşı korumalı. */
    private static final int MAX_TEXT_CHARS = 16_000;

    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final Clock clock;
    private final BigDecimal inputPerMillion;
    private final BigDecimal cachedPerMillion;
    private final BigDecimal outputPerMillion;

    LlmCallLog(JdbcClient jdbc, JsonMapper mapper, Clock clock, BigDecimal inputPerMillion,
               BigDecimal cachedPerMillion, BigDecimal outputPerMillion) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.inputPerMillion = inputPerMillion;
        this.cachedPerMillion = cachedPerMillion;
        this.outputPerMillion = outputPerMillion;
    }

    void record(UUID callId, LlmCall call, String modelId, LlmUsage usage, Duration latency,
                String rawResponse, List<String> clamped, String error) {
        String prompt = call.instruction() + ' ' + call.untrustedData();
        BigDecimal cost = usage.cost(inputPerMillion, cachedPerMillion, outputPerMillion);
        try {
            jdbc.sql("""
                    INSERT INTO llm_call (
                        id, occurred_at, purpose, model_id, prompt_hash, prompt_chars,
                        input_tokens, cached_input_tokens, output_tokens, reasoning_tokens,
                        cost_usd, latency_ms, response_raw, clamped_fields, error, metadata)
                    VALUES (
                        :id, :occurredAt, :purpose, :modelId, :promptHash, :promptChars,
                        :inputTokens, :cachedInputTokens, :outputTokens, :reasoningTokens,
                        :cost, :latencyMs, :response, :clamped, :error, :metadata::jsonb)
                    """)
                    .param("id", callId)
                    .param("occurredAt", java.sql.Timestamp.from(clock.instant()))
                    .param("purpose", call.purpose())
                    .param("modelId", modelId)
                    .param("promptHash", sha256(prompt))
                    .param("promptChars", prompt.length())
                    .param("inputTokens", usage.inputTokens())
                    .param("cachedInputTokens", usage.cachedInputTokens())
                    .param("outputTokens", usage.outputTokens())
                    .param("reasoningTokens", usage.reasoningTokens())
                    .param("cost", cost)
                    .param("latencyMs", (int) Math.min(latency.toMillis(), Integer.MAX_VALUE))
                    .param("response", truncate(rawResponse))
                    .param("clamped", clamped.toArray(String[]::new))
                    .param("error", truncate(error))
                    .param("metadata", mapper.writeValueAsString(call.metadata()))
                    .update();
        } catch (RuntimeException e) {
            log.error("LLM cagri kaydi yazilamadi (cagri yapildi, kayit kayboldu): id={} purpose={}",
                    callId, call.purpose(), e);
        }
    }

    void recordFailure(UUID callId, LlmCall call, String modelId, Duration latency, String error) {
        record(callId, call, modelId, LlmUsage.NONE, latency, null, List.of(), error);
    }

    /**
     * İçinde bulunulan aydaki harcamayı amaç kırılımıyla döndürür — bütçe sayacını
     * yeniden başlatmadan sonra geri yüklemek için.
     */
    Map<String, BigDecimal> spendThisMonth() {
        YearMonth month = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
        Instant start = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return jdbc.sql("""
                SELECT purpose, sum(cost_usd) AS total
                  FROM llm_call
                 WHERE occurred_at >= :start
                 GROUP BY purpose
                """)
                .param("start", java.sql.Timestamp.from(start))
                .query((rs, rowNum) -> Map.entry(rs.getString("purpose"), rs.getBigDecimal("total")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_TEXT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_TEXT_CHARS) + " [kesildi]";
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 yok", e);
        }
    }
}
