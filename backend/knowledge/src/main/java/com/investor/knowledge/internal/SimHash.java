package com.investor.knowledge.internal;

import java.util.HashMap;
import java.util.Map;

/**
 * 64 bitlik SimHash.
 *
 * <p>Tekilleştirmede ucuz ön eleme olarak kullanılıyor: benzer metinlerin parmak izleri
 * birbirine yakın olur, uzak olanlar kesinlikle farklıdır. Kesin kararı Jaccard veriyor —
 * kısa başlıklarda SimHash tek başına gürültülüdür, çünkü az sayıda belirteç tek bir bit
 * dönmesini büyütür.
 */
final class SimHash {

    private static final int BITS = 64;

    private SimHash() {
    }

    static long of(String text) {
        // Jaccard ile aynı belirteçler kullanılıyor: ön eleme ile kesin karar farklı
        // metin görürse, ön eleme kesin kararın bulacağı eşleşmeleri eleyebilir.
        Map<String, Integer> frequencies = new HashMap<>();
        for (String token : TextNormalizer.tokens(text)) {
            frequencies.merge(token, 1, Integer::sum);
        }
        if (frequencies.isEmpty()) {
            return 0L;
        }

        int[] vector = new int[BITS];
        frequencies.forEach((token, weight) -> {
            long hash = fnv1a(token);
            for (int bit = 0; bit < BITS; bit++) {
                vector[bit] += ((hash >>> bit) & 1L) == 1L ? weight : -weight;
            }
        });

        long fingerprint = 0L;
        for (int bit = 0; bit < BITS; bit++) {
            if (vector[bit] > 0) {
                fingerprint |= 1L << bit;
            }
        }
        return fingerprint;
    }

    static int hammingDistance(long left, long right) {
        return Long.bitCount(left ^ right);
    }

    /** FNV-1a 64 bit — hızlı, bağımlılıksız, tekilleştirme için yeterli dağılımda. */
    private static long fnv1a(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
