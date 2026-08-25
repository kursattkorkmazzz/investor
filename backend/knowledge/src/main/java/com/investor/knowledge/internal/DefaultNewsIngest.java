package com.investor.knowledge.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.investor.knowledge.NewsExtractor;
import com.investor.knowledge.NewsFeedSource;
import com.investor.knowledge.NewsIngest;
import com.investor.knowledge.model.NewsAnalysis;
import com.investor.knowledge.model.NewsClusterView;
import com.investor.knowledge.model.RawNewsItem;
import com.investor.ontology.model.ObjectRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Haber toplama akışı: çek → kanonikleştir → tekilleştir → kümelendir → çıkar → ontolojiye yaz.
 *
 * <h2>Tekilleştirme neden bu kadar önemli</h2>
 * Aynı haber beş kaynaktan gelir. Tekilleştirilmezse sistem bunu beş ayrı kanıt sayar ve
 * haberin <em>önemini</em> yayın <em>hacmiyle</em> karıştırır. Haber odaklı stratejilerdeki
 * en yaygın sistematik hata budur ve hiçbir yerde hata olarak görünmez — sadece popüler
 * konular sistematik olarak fazla ağırlık alır.
 *
 * <h2>Üç katmanlı tekilleştirme</h2>
 * <ol>
 *   <li><b>Kanonik URL</b> — aynı beslemeden aynı yazının tekrarı</li>
 *   <li><b>İçerik özeti</b> — farklı adres, birebir aynı metin</li>
 *   <li><b>Benzerlik kümelemesi</b> — farklı kaynak, aynı olay, farklı kelimeler</li>
 * </ol>
 */
class DefaultNewsIngest implements NewsIngest {

    private static final Logger log = LoggerFactory.getLogger(DefaultNewsIngest.class);

    private final NewsRepository repository;
    private final NewsFeedSource feedSource;
    private final NewsExtractor extractor;
    private final KnowledgeOntology ontology;
    private final Clock clock;
    private final double similarityThreshold;
    private final Duration clusterWindow;
    private final int candidateLimit;

    DefaultNewsIngest(NewsRepository repository, NewsFeedSource feedSource, NewsExtractor extractor,
                      KnowledgeOntology ontology, Clock clock,
                      double similarityThreshold, Duration clusterWindow, int candidateLimit) {
        this.repository = repository;
        this.feedSource = feedSource;
        this.extractor = extractor;
        this.ontology = ontology;
        this.clock = clock;
        this.similarityThreshold = similarityThreshold;
        this.clusterWindow = clusterWindow;
        this.candidateLimit = candidateLimit;
    }

    @Override
    public UUID registerFeed(String kind, String name, String url, double reliability) {
        ontology.ensureTypes();
        return repository.upsertFeed(kind, name, url, reliability);
    }

    @Override
    public IngestReport ingestAll() {
        ontology.ensureTypes();
        int fetched = 0;
        int newClusters = 0;
        int joined = 0;
        int skipped = 0;
        int failedFeeds = 0;

        for (NewsRepository.FeedRow feed : repository.enabledFeeds()) {
            try {
                List<RawNewsItem> items = feedSource.fetch(feed.url());
                repository.recordFeedSuccess(feed.id(), clock.instant());
                for (RawNewsItem item : items) {
                    fetched++;
                    Outcome outcome = ingestInternal(feed.id(), item);
                    switch (outcome) {
                        case NEW_CLUSTER -> newClusters++;
                        case JOINED -> joined++;
                        case DUPLICATE -> skipped++;
                    }
                }
            } catch (RuntimeException e) {
                failedFeeds++;
                repository.recordFeedFailure(feed.id(), clock.instant(), e.getMessage());
                // Bir beslemenin çökmesi diğerlerini durdurmaz; ingest'in tamamen susması
                // tek bir kaynağın eksik kalmasından kötüdür.
                log.warn("Besleme çekilemedi: {} ({})", feed.name(), e.getMessage());
            }
        }
        return new IngestReport(fetched, newClusters, joined, skipped, failedFeeds);
    }

    @Override
    public UUID ingestOne(UUID feedId, RawNewsItem item) {
        ontology.ensureTypes();
        ingestInternal(feedId, item);
        return feedId;
    }

    private enum Outcome {
        NEW_CLUSTER,
        JOINED,
        DUPLICATE
    }

    @Transactional
    Outcome ingestInternal(UUID feedId, RawNewsItem item) {
        String canonicalUrl = UrlCanonicalizer.canonicalize(item.url());
        String contentHash = sha256(TextNormalizer.normalize(
                item.title() + " " + Optional.ofNullable(item.body()).orElse("")));

        // 1. katman: aynı beslemeden aynı adres
        if (repository.urlSeen(feedId, canonicalUrl)) {
            return Outcome.DUPLICATE;
        }
        // 2. katman: farklı adres, birebir aynı içerik — kümeleme penceresiyle sınırlı
        if (repository.contentSeen(contentHash, item.publishedAt().minus(clusterWindow))) {
            return Outcome.DUPLICATE;
        }

        String normalizedText = TextNormalizer.normalize(item.similarityText());
        long simhash = SimHash.of(item.similarityText());
        Instant fetchedAt = clock.instant();

        Optional<NewsRepository.ClusterCandidate> match = findCluster(normalizedText, simhash, item);

        if (match.isPresent()) {
            NewsRepository.ClusterCandidate cluster = match.get();
            NewsRepository.ClusterJoin join = repository.joinCluster(cluster.id(), item.publishedAt());
            repository.insertItem(feedId, cluster.id(), canonicalUrl, item.title(), item.summary(),
                    item.body(), item.publishedAt(), fetchedAt, contentHash, simhash);

            // Yeni kaynak yalnızca sayacı büyütür — ikinci bir haber nesnesi doğmaz.
            // Geçerlilik başlangıcı kümenin en son yayın zamanı: sıra dışı gelen bir haber
            // geçmişe yazma çakışması üretmesin.
            if (cluster.objectId() != null) {
                ontology.updateSourceCount(ObjectRef.of(cluster.objectId()),
                        join.sourceCount(), join.lastPublishedAt());
            }
            return Outcome.JOINED;
        }

        UUID clusterId = repository.createCluster(simhash, normalizedText, item.publishedAt());
        repository.insertItem(feedId, clusterId, canonicalUrl, item.title(), item.summary(),
                item.body(), item.publishedAt(), fetchedAt, contentHash, simhash);

        NewsAnalysis analysis = extractor.analyze(item);
        ObjectRef article = ontology.writeArticle(externalIdFor(clusterId), item, analysis, 1, null);
        repository.attachObject(clusterId, article.id());
        return Outcome.NEW_CLUSTER;
    }

    /**
     * Aday kümeler arasında en benzerini bulur.
     *
     * <p>SimHash ön elemesi kaldırıldı: kısa başlıklarda az sayıda belirteç, benzer
     * metinlerde bile 20+ bitlik Hamming mesafesi üretiyor ve ön eleme doğru eşleşmeleri
     * eliyordu. Pencere zaten adayları birkaç yüzle sınırlıyor; küme kesişimi bu ölçekte
     * zaten ucuz. Parmak izi yine saklanıyor — korpus büyüyüp tarama pahalılaştığında
     * LSH bantlaması için gerekecek.
     *
     * <p>Kümenin karşılaştırma metni ilk üyesinden gelir ve değişmez. Konu evrilirse
     * temsilci metin geride kalır; şimdilik kabul edilebilir bir sadeleştirme.
     */
    private Optional<NewsRepository.ClusterCandidate> findCluster(String normalizedText, long simhash,
                                                                  RawNewsItem item) {
        Set<String> tokens = TextNormalizer.tokens(item.similarityText());
        if (tokens.isEmpty()) {
            return Optional.empty();
        }
        Instant since = item.publishedAt().minus(clusterWindow);

        NewsRepository.ClusterCandidate best = null;
        double bestScore = 0;
        for (NewsRepository.ClusterCandidate candidate : repository.recentCandidates(since, candidateLimit)) {
            double score = TextNormalizer.jaccard(tokens,
                    TextNormalizer.tokens(candidate.normalizedText()));
            if (score >= similarityThreshold && score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public List<NewsClusterView> recentClusters(int hours, int limit) {
        return repository.recentClusters(clock.instant().minus(Duration.ofHours(hours)), limit);
    }

    private static String externalIdFor(UUID clusterId) {
        return "NEWS:" + clusterId;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 bulunamadı", e);
        }
    }
}
