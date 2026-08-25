package com.investor.knowledge.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.investor.knowledge.NewsFeedSource;
import com.investor.knowledge.model.RawNewsItem;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP üzerinden RSS/Atom besleme kaynağı.
 *
 * <p>Yönlendirme takibi kapalı: bir beslemenin bizi keyfî adreslere yönlendirebilmesi,
 * dış içeriğin isteğin hedefini belirlemesi demektir.
 */
class HttpNewsFeedSource implements NewsFeedSource {

    /** Bir beslemeden kabul edilen azami gövde — bellek tüketen cevaplara karşı. */
    private static final int MAX_BODY_CHARS = 4_000_000;

    private final RestClient http;
    private final SyndicationFeedParser parser;
    private final Clock clock;

    HttpNewsFeedSource(Duration timeout, SyndicationFeedParser parser, Clock clock) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                        .build());
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().requestFactory(factory).build();
        this.parser = parser;
        this.clock = clock;
    }

    @Override
    public List<RawNewsItem> fetch(String feedUrl) {
        String body = http.get().uri(feedUrl)
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new FeedFetchException("Besleme %d döndürdü: %s"
                            .formatted(response.getStatusCode().value(), feedUrl));
                })
                .body(String.class);

        if (body == null || body.isBlank()) {
            throw new FeedFetchException("Besleme boş gövde döndürdü: " + feedUrl);
        }
        if (body.length() > MAX_BODY_CHARS) {
            throw new FeedFetchException("Besleme gövdesi çok büyük (%d karakter): %s"
                    .formatted(body.length(), feedUrl));
        }
        return parser.parse(body, clock.instant());
    }

    static class FeedFetchException extends RuntimeException {
        FeedFetchException(String message) {
            super(message);
        }
    }
}
