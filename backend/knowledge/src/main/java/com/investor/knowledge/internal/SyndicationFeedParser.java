package com.investor.knowledge.internal;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.investor.knowledge.model.RawNewsItem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * RSS 2.0 ve Atom ayrıştırıcı.
 *
 * <h2>Güvenlik</h2>
 * Besleme içeriği düşman girdisidir; XML ayrıştırıcı buna göre kısıtlanmıştır. Harici
 * varlık (entity) çözümlemesi ve DOCTYPE kapalı: açık bırakılırsa kötü niyetli bir
 * besleme, sunucudaki dosyaları okutabilir (XXE) veya özyinelemeli varlıklarla belleği
 * tüketebilir (billion laughs).
 *
 * <p>Harici bir kütüphane yerine JDK kullanılıyor — RSS/Atom yeterince basit ve dış
 * ayrıştırıcılar bu güvenlik ayarlarını varsayılan olarak açık bırakabiliyor.
 */
class SyndicationFeedParser {

    private static final Logger log = LoggerFactory.getLogger(SyndicationFeedParser.class);

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_ZONED_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH));

    List<RawNewsItem> parse(String xml, Instant fallbackPublishedAt) {
        Document document = parseSecurely(xml);
        List<RawNewsItem> items = new ArrayList<>();

        // RSS 2.0
        collect(document.getElementsByTagName("item"), items, fallbackPublishedAt,
                "link", "title", "description", List.of("content:encoded", "description"),
                List.of("pubDate", "dc:date"));

        // Atom
        if (items.isEmpty()) {
            collectAtom(document.getElementsByTagName("entry"), items, fallbackPublishedAt);
        }

        items.sort(Comparator.comparing(RawNewsItem::publishedAt));
        return List.copyOf(items);
    }

    private Document parseSecurely(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new FeedParseException("Besleme ayrıştırılamadı", e);
        }
    }

    private void collect(NodeList nodes, List<RawNewsItem> items, Instant fallback,
                         String linkTag, String titleTag, String summaryTag,
                         List<String> bodyTags, List<String> dateTags) {
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            String url = text(element, linkTag);
            String title = text(element, titleTag);
            if (url == null || url.isBlank() || title == null || title.isBlank()) {
                log.debug("Başlığı ya da adresi olmayan besleme kaydı atlandı");
                continue;
            }
            items.add(new RawNewsItem(url, title,
                    text(element, summaryTag),
                    firstNonBlank(element, bodyTags),
                    parseDate(firstNonBlank(element, dateTags), fallback)));
        }
    }

    private void collectAtom(NodeList nodes, List<RawNewsItem> items, Instant fallback) {
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            String url = atomLink(element);
            String title = text(element, "title");
            if (url == null || url.isBlank() || title == null || title.isBlank()) {
                continue;
            }
            items.add(new RawNewsItem(url, title,
                    text(element, "summary"),
                    firstNonBlank(element, List.of("content", "summary")),
                    parseDate(firstNonBlank(element, List.of("published", "updated")), fallback)));
        }
    }

    private static String atomLink(Element entry) {
        NodeList links = entry.getElementsByTagName("link");
        for (int i = 0; i < links.getLength(); i++) {
            if (links.item(i) instanceof Element link) {
                String rel = link.getAttribute("rel");
                if (rel.isEmpty() || "alternate".equals(rel)) {
                    String href = link.getAttribute("href");
                    if (!href.isBlank()) {
                        return href;
                    }
                }
            }
        }
        return text(entry, "id");
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        Node node = nodes.item(0);
        String content = node.getTextContent();
        return content == null ? null : content.trim();
    }

    private static String firstNonBlank(Element parent, List<String> tags) {
        for (String tag : tags) {
            String value = text(parent, tag);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * Yayın zamanını çözer; çözemezse verilen yedeğe düşer.
     *
     * <p>Yedek, çekme zamanıdır. Bu bir kayıptır ama yanlış tarih uydurmaktan iyidir:
     * gerçekte olduğundan eski görünen bir haber, backtest'te olmadığı bir anda görünür.
     */
    private static Instant parseDate(String raw, Instant fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return OffsetDateTime.parse(raw.trim(), format).toInstant();
            } catch (RuntimeException ignored) {
                try {
                    return ZonedDateTime.parse(raw.trim(), format).toInstant();
                } catch (RuntimeException alsoIgnored) {
                    // sonraki biçimi dene
                }
            }
        }
        try {
            return Instant.parse(raw.trim());
        } catch (RuntimeException e) {
            log.debug("Yayın zamanı çözülemedi: {}", raw);
            return fallback;
        }
    }

    static class FeedParseException extends RuntimeException {
        FeedParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
