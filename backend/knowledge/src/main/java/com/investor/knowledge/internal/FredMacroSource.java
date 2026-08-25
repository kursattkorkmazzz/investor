package com.investor.knowledge.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.investor.knowledge.MacroSource;
import com.investor.knowledge.model.MacroPoint;
import com.investor.knowledge.model.MacroSeriesSpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * FRED / ALFRED makro veri kaynağı.
 *
 * <h2>Neden vintage'lı çekiyoruz</h2>
 * FRED, {@code realtime_start}/{@code realtime_end} ile bir serinin <em>yayın
 * sürümlerini</em> verebiliyor: Temmuz CPI'ının 15 Ağustos'ta yayınlanan hâli ve
 * 15 Eylül'de düzeltilmiş hâli ayrı satırlar olarak geliyor.
 *
 * <p>Yalnızca güncel değeri çekmek çok daha kolay olurdu — ve backtest'i sistematik
 * olarak iyimser gösterirdi: bugün bildiğimiz düzeltilmiş rakamla, o gün elimizde
 * olmayan bir bilgiyle karar vermiş olurduk. Bu iki alan, ontolojinin bitemporal
 * geçerlilik aralığına birebir oturuyor.
 */
class FredMacroSource implements MacroSource {

    private static final Logger log = LoggerFactory.getLogger(FredMacroSource.class);

    /** FRED'in "hâlâ geçerli" işareti. */
    private static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);
    /** Serinin tüm yayın geçmişini isteyen alt sınır. */
    private static final String EARLIEST_REALTIME = "1776-07-04";
    /** Değeri olmayan gözlem. */
    private static final String MISSING_VALUE = ".";

    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String apiKey;

    FredMacroSource(String baseUrl, String apiKey, Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                        .build());
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey;
    }

    @Override
    public String sourceName() {
        return "FRED";
    }

    @Override
    public Optional<MacroSeriesSpec> describe(String seriesCode) {
        JsonNode body = get("/fred/series?series_id=%s&api_key=%s&file_type=json"
                .formatted(seriesCode, apiKey));
        JsonNode series = body.path("seriess");
        if (!series.isArray() || series.isEmpty()) {
            return Optional.empty();
        }
        JsonNode first = series.get(0);
        return Optional.of(new MacroSeriesSpec(
                first.path("id").asString(seriesCode),
                first.path("title").asString(seriesCode),
                first.path("units").asString(null),
                first.path("frequency_short").asString(null)));
    }

    @Override
    public List<MacroPoint> observations(String seriesCode, LocalDate since) {
        StringBuilder path = new StringBuilder("/fred/series/observations?series_id=%s&api_key=%s&file_type=json"
                .formatted(seriesCode, apiKey));
        path.append("&realtime_start=").append(EARLIEST_REALTIME).append("&realtime_end=").append(OPEN_ENDED);
        if (since != null) {
            path.append("&observation_start=").append(since);
        }

        JsonNode body = get(path.toString());
        List<Raw> raws = new ArrayList<>();
        for (JsonNode observation : body.path("observations")) {
            String rawValue = observation.path("value").asString("");
            if (rawValue.isBlank() || MISSING_VALUE.equals(rawValue)) {
                continue;   // FRED yayınlanmamış gözlemi "." ile bildirir
            }
            try {
                raws.add(new Raw(
                        LocalDate.parse(observation.path("date").asString()),
                        new BigDecimal(rawValue),
                        LocalDate.parse(observation.path("realtime_start").asString()),
                        LocalDate.parse(observation.path("realtime_end").asString())));
            } catch (RuntimeException e) {
                log.warn("{} serisinde ayrıştırılamayan gözlem atlandı: {}", seriesCode, observation);
            }
        }
        return toPoints(seriesCode, raws);
    }

    private record Raw(LocalDate period, BigDecimal value, LocalDate realtimeStart, LocalDate realtimeEnd) {
    }

    /**
     * Ham satırları sürümlere çevirir.
     *
     * <p>Bir dönemin ilk sürümü ilk yayın, sonrakiler düzeltmedir. Sıralama
     * {@code realtime_start}'a göre yapılıyor — FRED sırayı garanti etmiyor.
     */
    private static List<MacroPoint> toPoints(String seriesCode, List<Raw> raws) {
        Map<LocalDate, List<Raw>> byPeriod = new HashMap<>();
        raws.forEach(raw -> byPeriod.computeIfAbsent(raw.period(), key -> new ArrayList<>()).add(raw));

        List<MacroPoint> points = new ArrayList<>();
        byPeriod.forEach((period, vintages) -> {
            vintages.sort((left, right) -> left.realtimeStart().compareTo(right.realtimeStart()));
            for (int index = 0; index < vintages.size(); index++) {
                Raw raw = vintages.get(index);
                points.add(new MacroPoint(
                        seriesCode,
                        period,
                        raw.value(),
                        atUtcStartOfDay(raw.realtimeStart()),
                        OPEN_ENDED.equals(raw.realtimeEnd())
                                ? null
                                : atUtcStartOfDay(raw.realtimeEnd().plusDays(1)),
                        index > 0));
            }
        });
        points.sort((left, right) -> {
            int byPeriodOrder = left.period().compareTo(right.period());
            return byPeriodOrder != 0 ? byPeriodOrder : left.vintageFrom().compareTo(right.vintageFrom());
        });
        return List.copyOf(points);
    }

    /** FRED'in {@code realtime_end}'i dahildir; yarı açık aralığa çevirirken bir gün ekliyoruz. */
    private static Instant atUtcStartOfDay(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private JsonNode get(String path) {
        String body = http.get().uri(path)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new MacroSourceException("FRED %d döndürdü: %s"
                            .formatted(response.getStatusCode().value(), path.replace(apiKey, "***")));
                })
                .body(String.class);
        if (body == null || body.isBlank()) {
            throw new MacroSourceException("FRED boş gövde döndürdü");
        }
        return json.readTree(body);
    }

    static class MacroSourceException extends RuntimeException {
        MacroSourceException(String message) {
            super(message);
        }
    }
}
