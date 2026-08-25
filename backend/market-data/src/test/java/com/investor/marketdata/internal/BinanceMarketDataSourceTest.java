package com.investor.marketdata.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.investor.marketdata.model.Bar;
import com.investor.marketdata.model.InstrumentSpec;
import com.investor.marketdata.model.Timeframe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Binance adapter'ının sözleşme testi.
 *
 * <p>Gerçek API'ye karşı değil, kaydedilmiş cevap biçimine karşı koşuyor. Bu, ağa
 * bağımlı olmayan ve borsanın erişilebilirliğinden etkilenmeyen bir doğrulama sağlıyor —
 * ama <em>API'nin gerçekten bu biçimde cevap verdiğini kanıtlamıyor</em>. O doğrulama,
 * canlı bir uçtan uca duman testi gerektirir.
 */
@DisplayName("Binance adapter sözleşmesi")
class BinanceMarketDataSourceTest {

    private static final Instant NOW = Instant.parse("2026-03-15T12:10:00Z");

    private WireMockServer server;
    private BinanceMarketDataSource source;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        sleeps.clear();
        source = new BinanceMarketDataSource(
                RestClient.builder().baseUrl(server.baseUrl()).build(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                800,
                sleeps::add);
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    @DisplayName("kline dizisi doğru alanlara eşlenir")
    void klinesAreMappedCorrectly() {
        long openMs = Instant.parse("2026-03-15T12:00:00Z").toEpochMilli();
        long closeMs = Instant.parse("2026-03-15T12:00:59.999Z").toEpochMilli();
        server.stubFor(get(urlPathEqualTo("/api/v3/klines")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withHeader("x-mbx-used-weight-1m", "12")
                .withBody("""
                        [[%d,"67412.30","67480.00","67390.10","67455.25","38.42500000",
                          %d,"2591344.12345678",1284,"20.11000000","1356789.00000000","0"]]
                        """.formatted(openMs, closeMs))));

        List<Bar> bars = source.klines("BTCUSDT", Timeframe.M1,
                Instant.parse("2026-03-15T12:00:00Z"), Instant.parse("2026-03-15T12:05:00Z"), 500);

        assertThat(bars).singleElement().satisfies(bar -> {
            assertThat(bar.openTime()).isEqualTo(Instant.parse("2026-03-15T12:00:00Z"));
            assertThat(bar.closeTime()).isEqualTo(Instant.parse("2026-03-15T12:00:59.999Z"));
            assertThat(bar.open()).isEqualByComparingTo("67412.30");
            assertThat(bar.high()).isEqualByComparingTo("67480.00");
            assertThat(bar.low()).isEqualByComparingTo("67390.10");
            assertThat(bar.close()).isEqualByComparingTo("67455.25");
            assertThat(bar.volume()).isEqualByComparingTo("38.425");
            assertThat(bar.quoteVolume()).isEqualByComparingTo("2591344.12345678");
            assertThat(bar.tradeCount()).isEqualTo(1284);
            assertThat(bar.takerBuyBase()).isEqualByComparingTo("20.11");
            assertThat(bar.isFinal()).as("kapanış şu andan önce; mum kapanmış").isTrue();
        });

        // Binance'te endTime dahildir; yarı açık aralığımız 1 ms geri çekilerek gönderilmeli.
        server.verify(getRequestedFor(urlPathEqualTo("/api/v3/klines"))
                .withQueryParam("symbol", equalTo("BTCUSDT"))
                .withQueryParam("interval", equalTo("1m"))
                .withQueryParam("endTime", equalTo(
                        String.valueOf(Instant.parse("2026-03-15T12:05:00Z").toEpochMilli() - 1))));
    }

    @Test
    @DisplayName("kapanmamış mum isFinal=false olarak işaretlenir")
    void unclosedBarIsMarkedNonFinal() {
        // 12:10'da açılan mum 12:10:59.999'da kapanacak — şu an 12:10:00, henüz kapanmadı.
        long openMs = Instant.parse("2026-03-15T12:10:00Z").toEpochMilli();
        long closeMs = Instant.parse("2026-03-15T12:10:59.999Z").toEpochMilli();
        long prevOpen = Instant.parse("2026-03-15T12:09:00Z").toEpochMilli();
        long prevClose = Instant.parse("2026-03-15T12:09:59.999Z").toEpochMilli();

        server.stubFor(get(urlPathEqualTo("/api/v3/klines")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        [[%d,"1","2","0.5","1.5","10",%d,"15",5,"5","7","0"],
                         [%d,"1","2","0.5","1.5","10",%d,"15",5,"5","7","0"]]
                        """.formatted(prevOpen, prevClose, openMs, closeMs))));

        List<Bar> bars = source.klines("BTCUSDT", Timeframe.M1,
                Instant.parse("2026-03-15T12:09:00Z"), Instant.parse("2026-03-15T12:11:00Z"), 500);

        assertThat(bars).hasSize(2);
        assertThat(bars.get(0).isFinal()).isTrue();
        assertThat(bars.get(1).isFinal())
                .as("kaynak kapanmamış mumu gizlemez; ayrımı isFinal taşır")
                .isFalse();
    }

    @Test
    @DisplayName("üst sınırın dışındaki mumlar elenir")
    void barsBeyondUpperBoundAreDropped() {
        long inRange = Instant.parse("2026-03-15T12:00:00Z").toEpochMilli();
        long outOfRange = Instant.parse("2026-03-15T12:05:00Z").toEpochMilli();
        server.stubFor(get(urlPathEqualTo("/api/v3/klines")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        [[%d,"1","2","0.5","1.5","10",%d,"15",5,"5","7","0"],
                         [%d,"1","2","0.5","1.5","10",%d,"15",5,"5","7","0"]]
                        """.formatted(inRange, inRange + 59_999, outOfRange, outOfRange + 59_999))));

        List<Bar> bars = source.klines("BTCUSDT", Timeframe.M1,
                Instant.parse("2026-03-15T12:00:00Z"), Instant.parse("2026-03-15T12:05:00Z"), 500);

        assertThat(bars).singleElement()
                .satisfies(bar -> assertThat(bar.openTime())
                        .isEqualTo(Instant.parse("2026-03-15T12:00:00Z")));
    }

    @Test
    @DisplayName("exchangeInfo filtrelerinden emir kuralları çıkarılır")
    void exchangeInfoFiltersBecomeOrderRules() {
        server.stubFor(get(urlPathEqualTo("/api/v3/exchangeInfo")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"symbols":[
                          {"symbol":"BTCUSDT","status":"TRADING","baseAsset":"BTC","quoteAsset":"USDT",
                           "filters":[
                             {"filterType":"PRICE_FILTER","tickSize":"0.01000000"},
                             {"filterType":"LOT_SIZE","stepSize":"0.00001000"},
                             {"filterType":"NOTIONAL","minNotional":"5.00000000"},
                             {"filterType":"MAX_NUM_ORDERS","maxNumOrders":200}]},
                          {"symbol":"OLDCOIN","status":"BREAK","baseAsset":"OLD","quoteAsset":"USDT",
                           "filters":[]}
                        ]}
                        """)));

        List<InstrumentSpec> specs = source.instruments();

        assertThat(specs).hasSize(2);
        assertThat(specs.get(0)).satisfies(spec -> {
            assertThat(spec.exchange()).isEqualTo("BINANCE");
            assertThat(spec.symbol()).isEqualTo("BTCUSDT");
            assertThat(spec.status()).isEqualTo(InstrumentSpec.InstrumentStatus.TRADING);
            assertThat(spec.tickSize()).isEqualByComparingTo("0.01");
            assertThat(spec.stepSize()).isEqualByComparingTo("0.00001");
            assertThat(spec.minNotional()).isEqualByComparingTo("5");
        });
        assertThat(specs.get(1).status())
                .as("BREAK durumu işlem yapılamaz demek")
                .isEqualTo(InstrumentSpec.InstrumentStatus.HALT);
    }

    @Test
    @DisplayName("ağırlık bütçesi aşılınca yavaşlanır")
    void weightBudgetTriggersBackpressure() {
        server.stubFor(get(urlPathEqualTo("/api/v3/exchangeInfo")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withHeader("x-mbx-used-weight-1m", "950")
                .withBody("{\"symbols\":[]}")));

        source.instruments();

        assertThat(sleeps)
                .as("emir gönderme yolu için rezerv bırakmak, veri gecikmesinden önemli")
                .isNotEmpty();
    }

    @Test
    @DisplayName("hata durumları anlamlı istisnaya çevrilir")
    void errorStatusesBecomeTypedException() {
        server.stubFor(get(urlPathEqualTo("/api/v3/klines")).willReturn(aResponse()
                .withStatus(429)
                .withHeader("Retry-After", "30")));

        assertThatThrownBy(() -> source.klines("BTCUSDT", Timeframe.M1,
                Instant.parse("2026-03-15T12:00:00Z"), Instant.parse("2026-03-15T12:05:00Z"), 500))
                .isInstanceOf(BinanceMarketDataSource.MarketDataSourceException.class)
                .hasMessageContaining("429")
                .hasMessageContaining("30");
    }

    @Test
    @DisplayName("istek sınırı borsanın tavanını aşamaz")
    void requestLimitIsCapped() {
        server.stubFor(get(urlPathEqualTo("/api/v3/klines")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("[]")));

        source.klines("BTCUSDT", Timeframe.M1,
                Instant.parse("2026-03-15T12:00:00Z"), Instant.parse("2026-03-15T13:00:00Z"), 99_999);

        server.verify(getRequestedFor(urlPathEqualTo("/api/v3/klines"))
                .withQueryParam("limit", equalTo(String.valueOf(source.maxBarsPerRequest()))));
    }
}
