package com.investor.knowledge.internal;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * URL kanonikleştirme.
 *
 * <p>Aynı haber farklı izleme parametreleriyle defalarca gelir. Kanonikleştirilmemiş bir
 * URL, aynı yazıyı "yeni haber" saydırır ve tekilleştirmenin ilk katmanını devre dışı
 * bırakır.
 */
final class UrlCanonicalizer {

    /** İçeriği değiştirmeyen, yalnızca izleme amaçlı parametreler. */
    private static final Set<String> TRACKING_PARAMS = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
            "fbclid", "gclid", "msclkid", "mc_cid", "mc_eid", "ref", "source", "cmpid", "s_cid");

    private UrlCanonicalizer() {
    }

    static String canonicalize(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }

            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            String query = cleanQuery(uri.getQuery());
            // Parça (fragment) hiçbir zaman içeriği belirlemez; her zaman atılır.
            return scheme + "://" + host + path + (query.isEmpty() ? "" : "?" + query);
        } catch (RuntimeException e) {
            // Ayrıştırılamayan adresi kaybetmiyoruz; kırpılmış hâliyle kimlik olarak kullanıyoruz.
            return rawUrl.trim();
        }
    }

    private static String cleanQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        return Arrays.stream(query.split("&"))
                .filter(param -> !param.isBlank())
                .filter(param -> {
                    String name = param.contains("=") ? param.substring(0, param.indexOf('=')) : param;
                    return !TRACKING_PARAMS.contains(name.toLowerCase(Locale.ROOT));
                })
                .sorted()   // parametre sırası içeriği değiştirmez
                .collect(Collectors.joining("&"));
    }
}
