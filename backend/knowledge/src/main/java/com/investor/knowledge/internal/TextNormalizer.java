package com.investor.knowledge.internal;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Benzerlik karşılaştırması için metin normalleştirme.
 *
 * <p>Küçük harfe çevirme {@link Locale#ROOT} ile yapılıyor: Türkçe yerel ayarında
 * {@code "I".toLowerCase()} noktasız {@code ı} üretir ve aynı metin, uygulamanın
 * çalıştığı yerel ayara göre farklı tekilleştirilir. Aynı girdinin her yerde aynı
 * sonucu vermesi, tekilleştirmenin temel şartı.
 */
final class TextNormalizer {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final int MIN_TOKEN_LENGTH = 3;
    /** Gövdeleme sonrası kalması gereken asgari kök; aşırı kısalma anlamsız çakışma üretir. */
    private static final int MIN_STEM_LENGTH = 4;

    /**
     * Konu taşımayan sık kelimeler.
     *
     * <p>Bunları elemek benzerlik ölçümünü belirgin şekilde düzeltiyor: "the", "their",
     * "on" gibi kelimeler iki başlığın birleşim kümesini şişirip Jaccard'ı sistematik
     * olarak düşürüyor. Liste kasten kısa — konu taşımadığı tartışmasız olanlar.
     */
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "from", "that", "this", "their", "there",
            "into", "over", "after", "before", "once", "again", "since", "than", "then",
            "was", "were", "are", "has", "have", "had", "its", "his", "her", "will",
            "not", "but", "all", "any", "new", "now", "out", "off", "own", "per",
            "says", "said", "say", "amid", "also", "more", "most", "some", "such",
            "who", "why", "how", "what", "when", "where", "which", "while", "you");

    private TextNormalizer() {
    }

    static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
        String withoutMarks = DIACRITICS.matcher(decomposed).replaceAll("");
        return NON_ALNUM.matcher(withoutMarks.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /**
     * İçerik belirteçleri: kısa kelimeler ve durak kelimeler elenir, kaba gövdeleme uygulanır.
     *
     * <p>Gövdeleme olmadan "delays", "delay" ve "delayed" üç ayrı belirteç sayılır ve aynı
     * olayı anlatan iki başlığın benzerliği yapay olarak düşer. Tam bir gövdeleyici (Porter
     * gibi) daha isabetli olurdu; ek bağımlılığın maliyeti bu ölçekte kazancından büyük.
     */
    static Set<String> tokens(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        Arrays.stream(normalized.split(" "))
                .filter(token -> token.length() >= MIN_TOKEN_LENGTH)
                .filter(token -> !STOPWORDS.contains(token))
                .map(TextNormalizer::stem)
                .forEach(tokens::add);
        return tokens;
    }

    /**
     * Kaba İngilizce gövdeleme.
     *
     * <p>Yalnızca kök yeterince uzun kalıyorsa kesiyoruz: "mining" &rarr; "min" gibi aşırı
     * kısalmalar, alakasız kelimeleri aynı belirtece indirger ve yanlış birleştirme üretir.
     * Yanlış birleştirme, yanlış ayırmadan daha tehlikeli — iki ayrı olayı tek kanıt sayar.
     */
    static String stem(String token) {
        for (String suffix : List.of("ies", "ing", "ed", "es", "s")) {
            if (token.endsWith(suffix)) {
                String stem = token.substring(0, token.length() - suffix.length());
                if (stem.length() >= MIN_STEM_LENGTH) {
                    return "ies".equals(suffix) ? stem + "y" : stem;
                }
            }
        }
        return token;
    }

    /** İki belirteç kümesinin Jaccard benzerliği: kesişim / birleşim. */
    static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        int intersection = 0;
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = smaller == left ? right : left;
        for (String token : smaller) {
            if (larger.contains(token)) {
                intersection++;
            }
        }
        int union = left.size() + right.size() - intersection;
        return union == 0 ? 0 : (double) intersection / union;
    }
}
