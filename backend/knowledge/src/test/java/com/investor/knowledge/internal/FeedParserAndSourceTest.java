package com.investor.knowledge.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.investor.knowledge.model.MacroPoint;
import com.investor.knowledge.model.RawNewsItem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Besleme ayrıştırıcısı ve FRED adapter'ının sözleşme testleri.
 *
 * <p>Kaydedilmiş cevap biçimlerine karşı koşuyor; gerçek kaynakların bu biçimde cevap
 * verdiğini kanıtlamıyor. Bu ortamdan dış ağa erişim politikayla engelli.
 */
class FeedParserAndSourceTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");

    @Nested
    @DisplayName("RSS/Atom ayrıştırıcı")
    class Parser {

        private final SyndicationFeedParser parser = new SyndicationFeedParser();

        @Test
        @DisplayName("RSS 2.0 kayıtları çözülür ve yayın zamanına göre sıralanır")
        void parsesRss() {
            List<RawNewsItem> items = parser.parse("""
                    <?xml version="1.0"?>
                    <rss version="2.0"><channel>
                      <item>
                        <title>SEC delays Bitcoin ETF decision</title>
                        <link>https://outlet.test/etf?utm_source=rss</link>
                        <description>The decision was pushed back again.</description>
                        <pubDate>Wed, 19 Aug 2026 14:30:00 GMT</pubDate>
                      </item>
                      <item>
                        <title>Fed holds rates steady</title>
                        <link>https://outlet.test/fed</link>
                        <pubDate>Tue, 18 Aug 2026 18:00:00 GMT</pubDate>
                      </item>
                    </channel></rss>
                    """, NOW);

            assertThat(items).hasSize(2);
            assertThat(items.get(0).title()).isEqualTo("Fed holds rates steady");
            assertThat(items.get(1).publishedAt()).isEqualTo(Instant.parse("2026-08-19T14:30:00Z"));
            assertThat(items.get(1).summary()).contains("pushed back");
        }

        @Test
        @DisplayName("Atom kayıtları çözülür")
        void parsesAtom() {
            List<RawNewsItem> items = parser.parse("""
                    <?xml version="1.0"?>
                    <feed xmlns="http://www.w3.org/2005/Atom">
                      <entry>
                        <title>Ethereum upgrade goes live</title>
                        <link rel="alternate" href="https://outlet.test/eth"/>
                        <summary>Mainnet upgrade completed.</summary>
                        <published>2026-08-19T10:00:00Z</published>
                      </entry>
                    </feed>
                    """, NOW);

            assertThat(items).singleElement().satisfies(item -> {
                assertThat(item.url()).isEqualTo("https://outlet.test/eth");
                assertThat(item.publishedAt()).isEqualTo(Instant.parse("2026-08-19T10:00:00Z"));
            });
        }

        @Test
        @DisplayName("harici varlık bildirimi reddedilir")
        void rejectsExternalEntities() {
            // Besleme içeriği düşman girdisidir: DOCTYPE açık bırakılsaydı bu belge
            // sunucudaki /etc/passwd dosyasını okutabilirdi (XXE).
            String malicious = """
                    <?xml version="1.0"?>
                    <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                    <rss version="2.0"><channel>
                      <item><title>&xxe;</title><link>https://a.test/1</link></item>
                    </channel></rss>
                    """;

            assertThatThrownBy(() -> parser.parse(malicious, NOW))
                    .isInstanceOf(SyndicationFeedParser.FeedParseException.class);
        }

        @Test
        @DisplayName("başlığı ya da adresi olmayan kayıtlar atlanır")
        void skipsIncompleteEntries() {
            List<RawNewsItem> items = parser.parse("""
                    <?xml version="1.0"?>
                    <rss version="2.0"><channel>
                      <item><title>Başlıksız değil ama linksiz</title></item>
                      <item><link>https://a.test/1</link></item>
                      <item><title>Tam kayıt</title><link>https://a.test/2</link></item>
                    </channel></rss>
                    """, NOW);

            assertThat(items).singleElement()
                    .satisfies(item -> assertThat(item.title()).isEqualTo("Tam kayıt"));
        }

        @Test
        @DisplayName("çözülemeyen tarih çekme zamanına düşer")
        void unparseableDateFallsBackToFetchTime() {
            List<RawNewsItem> items = parser.parse("""
                    <?xml version="1.0"?>
                    <rss version="2.0"><channel>
                      <item><title>Tarihsiz</title><link>https://a.test/1</link>
                            <pubDate>bilinmeyen biçim</pubDate></item>
                    </channel></rss>
                    """, NOW);

            assertThat(items).singleElement()
                    .satisfies(item -> assertThat(item.publishedAt()).isEqualTo(NOW));
        }
    }

    @Nested
    @DisplayName("FRED adapter")
    class Fred {

        private WireMockServer server;
        private FredMacroSource source;

        @BeforeEach
        void start() {
            server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
            server.start();
            source = new FredMacroSource(server.baseUrl(), "test-key", Duration.ofSeconds(5));
        }

        @AfterEach
        void stop() {
            server.stop();
        }

        @Test
        @DisplayName("yayın sürümleri geçerlilik aralıklarına çevrilir")
        void vintagesBecomeValidityRanges() {
            server.stubFor(get(urlPathEqualTo("/fred/series/observations")).willReturn(aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("""
                            {"observations":[
                              {"realtime_start":"2026-08-15","realtime_end":"2026-09-14",
                               "date":"2026-07-01","value":"314.2"},
                              {"realtime_start":"2026-09-15","realtime_end":"9999-12-31",
                               "date":"2026-07-01","value":"314.5"},
                              {"realtime_start":"2026-09-15","realtime_end":"9999-12-31",
                               "date":"2026-08-01","value":"."}
                            ]}
                            """)));

            List<MacroPoint> points = source.observations("CPIAUCSL", null);

            assertThat(points)
                    .as("değeri olmayan gözlem ('.') atlanmalı")
                    .hasSize(2);

            assertThat(points.get(0)).satisfies(point -> {
                assertThat(point.period()).isEqualTo(LocalDate.of(2026, 7, 1));
                assertThat(point.value()).isEqualByComparingTo(new BigDecimal("314.2"));
                assertThat(point.vintageFrom()).isEqualTo(Instant.parse("2026-08-15T00:00:00Z"));
                assertThat(point.vintageTo())
                        .as("FRED'in realtime_end'i dahildir; yarı açık aralığa çevrilirken bir gün eklenir")
                        .isEqualTo(Instant.parse("2026-09-15T00:00:00Z"));
                assertThat(point.revision()).isFalse();
            });

            assertThat(points.get(1)).satisfies(point -> {
                assertThat(point.value()).isEqualByComparingTo(new BigDecimal("314.5"));
                assertThat(point.vintageTo()).as("hâlâ geçerli").isNull();
                assertThat(point.revision()).isTrue();
            });

            // Tüm yayın geçmişi istenmeli; yalnızca güncel değer backtest'i iyimser yapar.
            server.verify(getRequestedFor(urlPathEqualTo("/fred/series/observations"))
                    .withQueryParam("realtime_start", matching("1776-07-04"))
                    .withQueryParam("realtime_end", matching("9999-12-31")));
        }

        @Test
        @DisplayName("seri tanımı okunur")
        void readsSeriesMetadata() {
            server.stubFor(get(urlPathEqualTo("/fred/series")).willReturn(aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("""
                            {"seriess":[{"id":"CPIAUCSL","title":"Consumer Price Index",
                                         "units":"Index 1982-1984=100","frequency_short":"M"}]}
                            """)));

            assertThat(source.describe("CPIAUCSL")).hasValueSatisfying(spec -> {
                assertThat(spec.code()).isEqualTo("CPIAUCSL");
                assertThat(spec.displayName()).isEqualTo("Consumer Price Index");
                assertThat(spec.frequency()).isEqualTo("M");
            });
        }

        @Test
        @DisplayName("hata cevabında API anahtarı sızmaz")
        void apiKeyIsNotLeakedInErrors() {
            server.stubFor(get(urlPathEqualTo("/fred/series/observations"))
                    .willReturn(aResponse().withStatus(400)));

            assertThatThrownBy(() -> source.observations("CPIAUCSL", null))
                    .isInstanceOf(FredMacroSource.MacroSourceException.class)
                    .hasMessageContaining("400")
                    .hasMessageNotContaining("test-key");
        }
    }
}
