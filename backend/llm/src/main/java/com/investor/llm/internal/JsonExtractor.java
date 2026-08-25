package com.investor.llm.internal;

/**
 * Model cevabındaki JSON gövdesini bulur.
 *
 * <p>"Sadece JSON döndür" talimatına rağmen modeller cevabı markdown kod bloğuna sarabiliyor
 * ya da önüne bir cümle koyabiliyor. Katı şema desteği olan uç noktalarda bu olmaz; demo
 * uç noktasında ve şema desteklemeyen sağlayıcılarda olur. Ayrıştırmayı bu yüzden toleranslı
 * yapıyoruz — ama <em>yalnızca</em> sarmalayıcıyı soyacak kadar; içeriği düzeltmeye
 * kalkışmıyoruz. Bozuk JSON'u tahminle tamir etmek, modelin ne dediğini bizim uydurmamız olurdu.
 */
final class JsonExtractor {

    private JsonExtractor() {
    }

    static String extract(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.strip();
        text = stripFence(text);
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private static String stripFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }
        String body = text.substring(firstNewline + 1);
        int closing = body.lastIndexOf("```");
        return closing >= 0 ? body.substring(0, closing).strip() : body.strip();
    }
}
