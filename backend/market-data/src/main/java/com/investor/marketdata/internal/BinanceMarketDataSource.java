package com.investor.marketdata.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.investor.marketdata.MarketDataSource;
import com.investor.marketdata.model.Bar;
import com.investor.marketdata.model.InstrumentSpec;
import com.investor.marketdata.model.InstrumentSpec.InstrumentStatus;
import com.investor.marketdata.model.Timeframe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Binance Spot REST kaynağı.
 *
 * <p>Yalnızca genel (public) uç noktaları kullanır — API anahtarı gerektirmez. Veri
 * toplama ile emir gönderme ayrı portlar olduğu için, ingest salt-okunur koşabiliyor.
 *
 * <h2>Kapanmamış mum</h2>
 * Binance, aralık şu anı içeriyorsa son eleman olarak kapanmamış mumu da döner. Bunu
 * gizlemiyoruz — canlı takip için gerekli. Ayrım {@link Bar#isFinal()} ile yapılır ve
 * karar üretimi yalnızca kapanmışları okur.
 *
 * <h2>Ağırlık (weight) bütçesi</h2>
 * Binance istekleri ağırlıkla sınırlar ve kullanılan ağırlığı cevap başlığında bildirir.
 * Eşiğin üstüne çıkıldığında yavaşlıyoruz: emir gönderme yolunun veri toplama yüzünden
 * rate limit'e takılması, veri gecikmesinden çok daha pahalıdır.
 */
class BinanceMarketDataSource implements MarketDataSource {

    private static final Logger log = LoggerFactory.getLogger(BinanceMarketDataSource.class);

    private static final String EXCHANGE = "BINANCE";
    private static final int MAX_BARS = 1000;
    private static final String USED_WEIGHT_HEADER = "x-mbx-used-weight-1m";

    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock;
    private final int weightBudget;
    private final Sleeper sleeper;

    BinanceMarketDataSource(RestClient http, Clock clock, int weightBudget, Sleeper sleeper) {
        this.http = http;
        this.clock = clock;
        this.weightBudget = weightBudget;
        this.sleeper = sleeper;
    }

    /** Testlerde gerçek beklemeyi devre dışı bırakabilmek için. */
    interface Sleeper {
        void sleep(Duration duration);

        Sleeper REAL = duration -> {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MarketDataSourceException("bekleme kesildi", e);
            }
        };
    }

    @Override
    public String exchangeName() {
        return EXCHANGE;
    }

    @Override
    public int maxBarsPerRequest() {
        return MAX_BARS;
    }

    @Override
    public List<InstrumentSpec> instruments() {
        JsonNode body = get("/api/v3/exchangeInfo");
        List<InstrumentSpec> specs = new ArrayList<>();
        for (JsonNode symbol : body.path("symbols")) {
            specs.add(toSpec(symbol));
        }
        return List.copyOf(specs);
    }

    @Override
    public List<Bar> klines(String symbol, Timeframe timeframe,
                            Instant fromInclusive, Instant toExclusive, int limit) {
        int capped = Math.min(limit <= 0 ? MAX_BARS : limit, MAX_BARS);
        // Binance'te endTime dahildir; yarı açık aralığımızı 1 ms geri çekiyoruz.
        String path = "/api/v3/klines?symbol=%s&interval=%s&startTime=%d&endTime=%d&limit=%d".formatted(
                symbol, timeframe.code(),
                fromInclusive.toEpochMilli(),
                toExclusive.toEpochMilli() - 1,
                capped);

        JsonNode body = get(path);
        Instant now = clock.instant();
        List<Bar> bars = new ArrayList<>();
        for (JsonNode row : body) {
            Bar bar = toBar(row, timeframe, now);
            if (bar.openTime().isBefore(toExclusive)) {
                bars.add(bar);
            }
        }
        return List.copyOf(bars);
    }

    // ------------------------------------------------------------------

    private JsonNode get(String path) {
        var response = http.get().uri(path)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, res) -> {
                    // 429: hız sınırı, 418: geçici ban. İkisi de "yavaşla" demek.
                    int status = res.getStatusCode().value();
                    String retryAfter = res.getHeaders().getFirst("Retry-After");
                    throw new MarketDataSourceException(
                            "Binance %d döndürdü (path=%s, retryAfter=%s)".formatted(status, path, retryAfter));
                })
                .toEntity(String.class);

        applyBackpressure(response.getHeaders().getFirst(USED_WEIGHT_HEADER));

        String payload = response.getBody();
        if (payload == null || payload.isBlank()) {
            throw new MarketDataSourceException("Binance boş gövde döndürdü: " + path);
        }
        return json.readTree(payload);
    }

    /**
     * Ağırlık bütçesinin çoğu tükendiyse yavaşla.
     *
     * <p>Emir gönderme yolu için rezerv bırakmak, veri toplamayı geciktirmekten önemli:
     * gecikmiş veri bir sonraki turda gelir, gönderilemeyen emir fırsatı kaçırır.
     */
    private void applyBackpressure(String usedWeightHeader) {
        if (usedWeightHeader == null || weightBudget <= 0) {
            return;
        }
        try {
            int used = Integer.parseInt(usedWeightHeader.trim());
            if (used > weightBudget) {
                log.warn("Binance ağırlık bütçesi aşıldı ({}/{}), ingest yavaşlatılıyor", used, weightBudget);
                sleeper.sleep(Duration.ofSeconds(5));
            }
        } catch (NumberFormatException ignored) {
            // Başlık beklenen biçimde değilse geri basınç uygulamıyoruz; veri yine doğru.
        }
    }

    private static InstrumentSpec toSpec(JsonNode symbol) {
        BigDecimal tickSize = BigDecimal.ONE.movePointLeft(8);
        BigDecimal stepSize = BigDecimal.ONE.movePointLeft(8);
        BigDecimal minNotional = BigDecimal.ZERO;

        for (JsonNode filter : symbol.path("filters")) {
            switch (filter.path("filterType").asString("")) {
                case "PRICE_FILTER" -> tickSize = decimal(filter, "tickSize", tickSize);
                case "LOT_SIZE" -> stepSize = decimal(filter, "stepSize", stepSize);
                case "NOTIONAL", "MIN_NOTIONAL" -> minNotional = decimal(filter, "minNotional", minNotional);
                default -> { /* diğer filtreler emir boyutlandırmayı etkilemiyor */ }
            }
        }

        return new InstrumentSpec(
                EXCHANGE,
                symbol.path("symbol").asString(""),
                symbol.path("baseAsset").asString(""),
                symbol.path("quoteAsset").asString(""),
                status(symbol.path("status").asString("")),
                tickSize, stepSize, minNotional);
    }

    private static InstrumentStatus status(String raw) {
        return switch (raw) {
            case "TRADING" -> InstrumentStatus.TRADING;
            case "HALT", "BREAK", "AUCTION_MATCH", "PRE_TRADING", "POST_TRADING" -> InstrumentStatus.HALT;
            case "DELISTED", "END_OF_DAY" -> InstrumentStatus.DELISTED;
            default -> InstrumentStatus.UNKNOWN;
        };
    }

    private static BigDecimal decimal(JsonNode node, String field, BigDecimal fallback) {
        String raw = node.path(field).asString("");
        return raw.isBlank() ? fallback : new BigDecimal(raw);
    }

    /**
     * Binance kline dizisi → {@link Bar}.
     *
     * <p>Dizi düzeni: [0] açılış zamanı, [1] open, [2] high, [3] low, [4] close,
     * [5] hacim, [6] kapanış zamanı, [7] karşı hacim, [8] işlem sayısı, [9] taker alış hacmi.
     */
    private static Bar toBar(JsonNode row, Timeframe timeframe, Instant now) {
        Instant openTime = Instant.ofEpochMilli(row.get(0).asLong());
        Instant closeTime = Instant.ofEpochMilli(row.get(6).asLong());
        return new Bar(
                0L, // enstrüman kimliği yazma sırasında atanır
                timeframe,
                openTime,
                closeTime,
                new BigDecimal(row.get(1).asString()),
                new BigDecimal(row.get(2).asString()),
                new BigDecimal(row.get(3).asString()),
                new BigDecimal(row.get(4).asString()),
                new BigDecimal(row.get(5).asString()),
                new BigDecimal(row.get(7).asString()),
                row.get(8).asInt(),
                new BigDecimal(row.get(9).asString()),
                closeTime.isBefore(now));
    }

    /** Kaynak hatası — çağıran tarafın devre kesiciye yönlendirebilmesi için ayrı tip. */
    static class MarketDataSourceException extends RuntimeException {
        MarketDataSourceException(String message) {
            super(message);
        }

        MarketDataSourceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
