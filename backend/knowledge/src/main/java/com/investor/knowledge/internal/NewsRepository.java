package com.investor.knowledge.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.investor.knowledge.model.NewsClusterView;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Haber toplama tablolarına erişim. */
class NewsRepository {

    private final JdbcClient jdbc;

    NewsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record FeedRow(UUID id, String kind, String name, String url, double reliability) {
    }

    /** Kümeleme adayı: son penceredeki kümelerin parmak izi ve metni. */
    record ClusterCandidate(UUID id, long simhash, String normalizedText, int sourceCount, UUID objectId) {
    }

    // ------------------------------------------------------------------ besleme

    UUID upsertFeed(String kind, String name, String url, double reliability) {
        return jdbc.sql("""
                INSERT INTO news_feed (kind, name, url, reliability)
                VALUES (:kind, :name, :url, :reliability)
                ON CONFLICT (url) DO UPDATE SET
                    kind = EXCLUDED.kind, name = EXCLUDED.name, reliability = EXCLUDED.reliability
                RETURNING id
                """)
                .param("kind", kind).param("name", name)
                .param("url", url).param("reliability", reliability)
                .query(UUID.class).single();
    }

    List<FeedRow> enabledFeeds() {
        return jdbc.sql("""
                SELECT id, kind, name, url, reliability FROM news_feed
                 WHERE enabled ORDER BY name
                """)
                .query((rs, n) -> new FeedRow(UUID.fromString(rs.getString("id")),
                        rs.getString("kind"), rs.getString("name"), rs.getString("url"),
                        rs.getDouble("reliability")))
                .list();
    }

    void recordFeedSuccess(UUID feedId, Instant at) {
        jdbc.sql("""
                UPDATE news_feed SET last_attempt_at = :at, last_success_at = :at,
                       consecutive_errors = 0, last_error = NULL
                 WHERE id = :id
                """).param("at", Timestamp.from(at)).param("id", feedId).update();
    }

    void recordFeedFailure(UUID feedId, Instant at, String error) {
        jdbc.sql("""
                UPDATE news_feed SET last_attempt_at = :at,
                       consecutive_errors = consecutive_errors + 1, last_error = :error
                 WHERE id = :id
                """)
                .param("at", Timestamp.from(at)).param("id", feedId)
                .param("error", error == null ? null : error.substring(0, Math.min(error.length(), 500)))
                .update();
    }

    // ------------------------------------------------------------------ haber

    /**
     * Birebir aynı içerik bu pencerede görüldü mü.
     *
     * <p>Kontrol zamana bağlı: bir hafta sonra aynı başlıkla yayınlanan haber, aynı
     * yazının tekrarı değil yeni bir olaydır.
     */
    boolean contentSeen(String contentHash, Instant since) {
        return jdbc.sql("""
                SELECT count(*) FROM news_item
                 WHERE content_hash = :hash AND published_at >= :since
                """)
                .param("hash", contentHash).param("since", Timestamp.from(since))
                .query(Long.class).single() > 0;
    }

    boolean urlSeen(UUID feedId, String canonicalUrl) {
        return jdbc.sql("SELECT count(*) FROM news_item WHERE feed_id = :feed AND canonical_url = :url")
                .param("feed", feedId).param("url", canonicalUrl).query(Long.class).single() > 0;
    }

    UUID insertItem(UUID feedId, UUID clusterId, String canonicalUrl, String title, String summary,
                    String body, Instant publishedAt, Instant fetchedAt, String contentHash, long simhash) {
        return jdbc.sql("""
                INSERT INTO news_item (feed_id, cluster_id, canonical_url, title, summary, body,
                                       published_at, fetched_at, content_hash, simhash)
                VALUES (:feed, :cluster, :url, :title, :summary, :body,
                        :publishedAt, :fetchedAt, :hash, :simhash)
                RETURNING id
                """)
                .param("feed", feedId).param("cluster", clusterId).param("url", canonicalUrl)
                .param("title", title).param("summary", summary).param("body", body)
                .param("publishedAt", Timestamp.from(publishedAt))
                .param("fetchedAt", Timestamp.from(fetchedAt))
                .param("hash", contentHash).param("simhash", simhash)
                .query(UUID.class).single();
    }

    // ------------------------------------------------------------------ küme

    /**
     * Son penceredeki kümeler.
     *
     * <p>Tüm kümelerle karşılaştırma yapmıyoruz: aynı haber günler sonra yeniden
     * yayınlanmaz, ve pencere olmadan karşılaştırma maliyeti zamanla lineer büyür.
     */
    List<ClusterCandidate> recentCandidates(Instant since, int limit) {
        return jdbc.sql("""
                SELECT id, simhash, normalized_text, source_count, object_id
                  FROM news_cluster
                 WHERE last_published_at >= :since
                 ORDER BY last_published_at DESC
                 LIMIT :limit
                """)
                .param("since", Timestamp.from(since)).param("limit", limit)
                .query((rs, n) -> new ClusterCandidate(
                        UUID.fromString(rs.getString("id")),
                        rs.getLong("simhash"),
                        rs.getString("normalized_text"),
                        rs.getInt("source_count"),
                        rs.getString("object_id") == null ? null
                                : UUID.fromString(rs.getString("object_id"))))
                .list();
    }

    UUID createCluster(long simhash, String normalizedText, Instant publishedAt) {
        return jdbc.sql("""
                INSERT INTO news_cluster (simhash, normalized_text, source_count,
                                          first_published_at, last_published_at)
                VALUES (:simhash, :text, 1, :at, :at)
                RETURNING id
                """)
                .param("simhash", simhash).param("text", normalizedText)
                .param("at", Timestamp.from(publishedAt))
                .query(UUID.class).single();
    }

    /**
     * Kümeye yeni kaynak ekler.
     *
     * @return güncel kaynak sayısı ve kümenin en son yayın zamanı. İkincisi gerekli:
     *         {@code sourceCount} ontolojiye yazılırken geçerlilik başlangıcı geriye
     *         gidemez, yoksa sıra dışı gelen bir haber geçmişe yazma çakışması üretir.
     */
    ClusterJoin joinCluster(UUID clusterId, Instant publishedAt) {
        return jdbc.sql("""
                UPDATE news_cluster
                   SET source_count = source_count + 1,
                       last_published_at = GREATEST(last_published_at, :at),
                       first_published_at = LEAST(first_published_at, :at)
                 WHERE id = :id
                RETURNING source_count, last_published_at
                """)
                .param("id", clusterId).param("at", Timestamp.from(publishedAt))
                .query((rs, n) -> new ClusterJoin(rs.getInt("source_count"),
                        rs.getObject("last_published_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    record ClusterJoin(int sourceCount, Instant lastPublishedAt) {
    }

    void attachObject(UUID clusterId, UUID objectId) {
        jdbc.sql("UPDATE news_cluster SET object_id = :object WHERE id = :id")
                .param("object", objectId).param("id", clusterId).update();
    }

    Optional<NewsClusterView> cluster(UUID clusterId) {
        return jdbc.sql("""
                SELECT c.id, c.object_id, c.source_count, c.first_published_at, c.last_published_at,
                       (SELECT i.title FROM news_item i WHERE i.cluster_id = c.id
                         ORDER BY i.published_at LIMIT 1) AS title
                  FROM news_cluster c WHERE c.id = :id
                """)
                .param("id", clusterId)
                .query(NewsRepository::toClusterView)
                .optional();
    }

    List<NewsClusterView> recentClusters(Instant since, int limit) {
        return jdbc.sql("""
                SELECT c.id, c.object_id, c.source_count, c.first_published_at, c.last_published_at,
                       (SELECT i.title FROM news_item i WHERE i.cluster_id = c.id
                         ORDER BY i.published_at LIMIT 1) AS title
                  FROM news_cluster c
                 WHERE c.last_published_at >= :since
                 ORDER BY c.last_published_at DESC
                 LIMIT :limit
                """)
                .param("since", Timestamp.from(since)).param("limit", limit)
                .query(NewsRepository::toClusterView)
                .list();
    }

    private static NewsClusterView toClusterView(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new NewsClusterView(
                UUID.fromString(rs.getString("id")),
                rs.getString("object_id") == null ? null : UUID.fromString(rs.getString("object_id")),
                rs.getString("title"),
                rs.getInt("source_count"),
                rs.getObject("first_published_at", OffsetDateTime.class).toInstant(),
                rs.getObject("last_published_at", OffsetDateTime.class).toInstant());
    }
}
