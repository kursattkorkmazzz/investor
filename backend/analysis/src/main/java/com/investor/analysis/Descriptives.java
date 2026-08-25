package com.investor.analysis;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Saf betimsel istatistik fonksiyonları.
 *
 * <p>Durumsuz ve yan etkisiz; her yerden çağrılabilir. Ayrı bir sınıfta olmalarının
 * sebebi, gösterge persentilinin de fiyat persentilinin de aynı tanımı kullanmasını
 * garanti etmek: iki yerde iki farklı persentil tanımı, aynı sayının iki farklı anlama
 * geldiği bir sistem demektir.
 */
public final class Descriptives {

    private Descriptives() {
    }

    /**
     * Persentil <em>sırası</em>: geçmiş gözlemlerin yüzde kaçı {@code current} değerinden küçük.
     *
     * <p>Tanım belirsizliği burada gerçek bir risk: "persentil" kelimesi en az üç farklı
     * şeyi anlatmak için kullanılıyor (kesin sıra, eşit değerleri yarım sayan sıra,
     * enterpolasyonlu değer). Sistem boyunca tek tanım kullanılıyor ve bu o tanım:
     * <em>eşit değerlerin yarısı sayılan sıra</em> — süreksiz serilerde daha kararlı.
     *
     * @return 0–100 arası; {@code values} boşsa boş
     */
    public static OptionalDouble percentileRank(double[] values, double current) {
        if (values.length == 0) {
            return OptionalDouble.empty();
        }
        int below = 0;
        int equal = 0;
        for (double v : values) {
            if (v < current) {
                below++;
            } else if (v == current) {
                equal++;
            }
        }
        return OptionalDouble.of(100.0 * (below + equal / 2.0) / values.length);
    }

    /**
     * z-skor: {@code current} değerin ortalamadan kaç standart sapma uzakta olduğu.
     *
     * <p>Standart sapma sıfırsa (tüm gözlemler aynı) sonuç tanımsız — sıfır dönmek yerine
     * boş dönüyoruz. Sıfır dönseydi "tam ortalamada" gibi okunurdu; oysa doğru okuma
     * "bu seride sapma diye bir şey yok, z-skor anlamsız".
     */
    public static OptionalDouble zScore(double[] values, double current) {
        if (values.length < 2) {
            return OptionalDouble.empty();
        }
        double mean = mean(values);
        double sd = standardDeviation(values, mean);
        if (sd == 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of((current - mean) / sd);
    }

    public static double mean(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    /** Örneklem standart sapması (n−1 bölen) — gözlemler bir örneklem, evren değil. */
    public static double standardDeviation(double[] values, double mean) {
        if (values.length < 2) {
            return 0;
        }
        double sumSq = 0;
        for (double v : values) {
            double d = v - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / (values.length - 1));
    }

    public static double standardDeviation(double[] values) {
        return standardDeviation(values, mean(values));
    }

    /**
     * Ardışık değerlerden logaritmik getiri dizisi.
     *
     * <p>Basit getiri ({@code p1/p0 − 1}) yerine log getiri: toplanabilir olduğu için
     * çok periyotlu birleştirme doğru çalışıyor ve simetrik — %50 düşüp %100 çıkmak
     * basit getiride +%50 görünürken log getiride sıfır.
     *
     * <p>Sıfır ya da negatif fiyat içeren çiftler atlanıyor; logaritması tanımsız.
     */
    public static double[] logReturns(double[] prices) {
        if (prices.length < 2) {
            return new double[0];
        }
        double[] out = new double[prices.length - 1];
        int n = 0;
        for (int i = 1; i < prices.length; i++) {
            if (prices[i - 1] > 0 && prices[i] > 0) {
                out[n++] = Math.log(prices[i] / prices[i - 1]);
            }
        }
        return Arrays.copyOf(out, n);
    }

    public static double[] toArray(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}
