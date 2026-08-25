package com.investor.knowledge;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.investor.knowledge.model.NewsClusterView;
import com.investor.knowledge.model.RawNewsItem;
import com.investor.knowledge.support.AbstractKnowledgeTest;
import com.investor.ontology.model.ObjectRef;
import com.investor.ontology.query.Filter;
import com.investor.ontology.query.OntologyQuery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faz-2 kapı testi: haber tekilleştirme.
 *
 * <p>İddia: aynı olay farklı kaynaklardan geldiğinde <em>tek</em> kanıt üretir, farklı
 * olaylar birbirine karışmaz.
 *
 * <p>Neden kapı: tekilleştirilmemiş bir haber akışında sistem, bir olayın önemini kaç
 * yerde yayınlandığıyla karıştırır. Popüler konular sistematik olarak fazla ağırlık alır
 * ve bu hiçbir yerde hata olarak görünmez — sadece kararlar kötüleşir.
 */
@DisplayName("Kapı testi: haber tekilleştirme")
class NewsDedupGateTest extends AbstractKnowledgeTest {

    private UUID feedId;

    @BeforeEach
    void registerFeed() {
        feedId = newsIngest.registerFeed("RSS", "Test Beslemesi", "https://example.test/rss", 0.7);
    }

    @Test
    @DisplayName("aynı olay beş kaynaktan gelince tek konu ve tek haber nesnesi olur")
    void sameStoryFromFiveSourcesBecomesOneCluster() {
        List<String> headlines = List.of(
                "SEC delays decision on spot Bitcoin ETF application",
                "SEC postpones spot Bitcoin ETF decision once again",
                "Spot Bitcoin ETF decision delayed by SEC regulators",
                "Regulators at the SEC delay their spot Bitcoin ETF decision",
                "SEC again delays its decision on the spot Bitcoin ETF");

        for (int i = 0; i < headlines.size(); i++) {
            ingest(headlines.get(i), "https://outlet" + i + ".test/story-" + i, T0.plusSeconds(i * 60L));
        }

        List<NewsClusterView> clusters = newsIngest.recentClusters(24, 50);
        assertThat(clusters)
                .as("beş kaynak tek olayı anlatıyor")
                .hasSize(1);
        assertThat(clusters.get(0).sourceCount()).isEqualTo(5);

        var articles = ontology.query(OntologyQuery.ofType("NewsArticle").build());
        assertThat(articles.total())
                .as("kanıt sayısı değil, kaynak sayısı artmalı")
                .isEqualTo(1);
        assertThat(ontology.current(ObjectRef.of(clusters.get(0).objectId())).orElseThrow()
                .getNumber("sourceCount").orElseThrow())
                .isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("işaretlenmiş örneklemde konular doğru ayrılır")
    void labelledSampleIsClusteredCorrectly() {
        Map<String, List<String>> topics = new LinkedHashMap<>();
        topics.put("etf-delay", List.of(
                "SEC delays decision on spot Bitcoin ETF application",
                "SEC postpones spot Bitcoin ETF decision once again",
                "Spot Bitcoin ETF decision delayed by SEC regulators"));
        topics.put("fed-rates", List.of(
                "Federal Reserve holds interest rates steady at March meeting",
                "Fed keeps interest rates steady in March meeting decision",
                "Interest rates held steady by the Federal Reserve in March"));
        topics.put("exchange-hack", List.of(
                "Crypto exchange loses 200 million dollars in security breach",
                "Security breach costs crypto exchange 200 million dollars"));
        topics.put("ethereum-upgrade", List.of(
                "Ethereum mainnet upgrade goes live after successful testnet run",
                "Successful testnet run precedes live Ethereum mainnet upgrade"));
        topics.put("etf-inflow", List.of(
                "Bitcoin ETF products record largest weekly inflow since launch"));
        topics.put("mining-difficulty", List.of(
                "Bitcoin mining difficulty reaches new all time high"));

        Map<String, UUID> assignedCluster = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, List<String>> topic : topics.entrySet()) {
            for (String headline : topic.getValue()) {
                ingest(headline, "https://outlet.test/item-" + index, T0.plusSeconds(index * 120L));
                assignedCluster.put(headline, clusterOf(headline));
                index++;
            }
        }

        // Aynı konudaki her çift aynı kümede olmalı
        List<String> mergeFailures = new ArrayList<>();
        topics.forEach((topic, headlines) -> {
            UUID first = assignedCluster.get(headlines.get(0));
            headlines.forEach(headline -> {
                if (!first.equals(assignedCluster.get(headline))) {
                    mergeFailures.add(topic + " → " + headline);
                }
            });
        });

        // Farklı konulardaki hiçbir çift aynı kümede olmamalı
        List<String> splitFailures = new ArrayList<>();
        List<Map.Entry<String, List<String>>> entries = new ArrayList<>(topics.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                for (String left : entries.get(i).getValue()) {
                    for (String right : entries.get(j).getValue()) {
                        if (assignedCluster.get(left).equals(assignedCluster.get(right))) {
                            splitFailures.add(left + " ↔ " + right);
                        }
                    }
                }
            }
        }

        assertThat(mergeFailures)
                .as("aynı olayın farklı anlatımları birleşmeli")
                .isEmpty();
        assertThat(splitFailures)
                .as("farklı olaylar birbirine karışmamalı — bu yön daha tehlikeli, "
                        + "iki ayrı olayı tek kanıt sayar")
                .isEmpty();
        assertThat(newsIngest.recentClusters(24, 50)).hasSize(topics.size());
    }

    @Test
    @DisplayName("izleme parametreleri aynı yazıyı yeni haber saydırmaz")
    void trackingParametersDoNotCreateDuplicates() {
        String url = "https://outlet.test/bitcoin-etf-delayed";
        ingest("SEC delays decision on spot Bitcoin ETF", url, T0);
        ingest("SEC delays decision on spot Bitcoin ETF",
                url + "?utm_source=twitter&utm_campaign=daily", T0.plusSeconds(30));
        ingest("SEC delays decision on spot Bitcoin ETF", url + "/?fbclid=abc123", T0.plusSeconds(60));

        assertThat(newsIngest.recentClusters(24, 50)).hasSize(1);
        assertThat(itemCount())
                .as("kanonik adres aynı olduğu için ikinci ve üçüncü kayıt hiç işlenmemeli")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("pencere dışındaki eski konu yeniden açılır")
    void storiesOutsideTheWindowStartNewClusters() {
        ingest("SEC delays decision on spot Bitcoin ETF", "https://a.test/1", T0);
        // Kümeleme penceresi 72 saat; bir hafta sonra aynı başlık yeni bir olaydır.
        ingest("SEC delays decision on spot Bitcoin ETF", "https://b.test/2",
                T0.plus(Duration.ofDays(7)));

        assertThat(newsIngest.recentClusters(24 * 30, 50)).hasSize(2);
    }

    @Test
    @DisplayName("haberin yayın zamanı ile öğrenme zamanı ayrı kaydedilir")
    void publishedAndFetchedTimesAreSeparate() {
        Instant published = T0.minus(Duration.ofHours(3));
        clock.setTo(T0);
        ingest("Federal Reserve holds interest rates steady", "https://a.test/fed", published);

        UUID objectId = newsIngest.recentClusters(24, 10).get(0).objectId();
        var history = ontology.history(ObjectRef.of(objectId), "title");

        assertThat(history).singleElement().satisfies(entry -> {
            assertThat(entry.validFrom())
                    .as("geçerlilik zamanı = yayın zamanı")
                    .isEqualTo(published);
            assertThat(entry.recordedAt())
                    .as("kayıt zamanı = bizim gördüğümüz an; backtest bu eksene bakar")
                    .isEqualTo(T0);
        });
    }

    @Test
    @DisplayName("çıkarım varlıkları ontolojiye bağlar")
    void extractionLinksEntities() {
        ingest("Bitcoin mining difficulty reaches new all time high", "https://a.test/btc", T0);

        var articles = ontology.query(OntologyQuery.ofType("NewsArticle")
                .where(Filter.isNotNull("title")).build());
        assertThat(articles.objects()).hasSize(1);
        assertThat(articles.objects().get(0).linked("MENTIONS"))
                .as("yapılandırmadaki anahtar kelime eşlemesi varlığa bağlamalı")
                .extracting(link -> link.targetExternalId())
                .contains("ASSET:BTC");
    }

    // ------------------------------------------------------------------

    private void ingest(String title, String url, Instant publishedAt) {
        newsIngest.ingestOne(feedId, new RawNewsItem(url, title, null, null, publishedAt));
    }

    private UUID clusterOf(String title) {
        return jdbc.sql("SELECT cluster_id FROM news_item WHERE title = :title")
                .param("title", title).query(UUID.class).single();
    }

    private long itemCount() {
        return jdbc.sql("SELECT count(*) FROM news_item").query(Long.class).single();
    }
}
