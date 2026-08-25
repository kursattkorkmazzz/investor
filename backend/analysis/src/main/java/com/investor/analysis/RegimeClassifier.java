package com.investor.analysis;

import com.investor.analysis.model.IndicatorSet;
import com.investor.analysis.model.PriceStats;
import com.investor.analysis.model.Regime;

/**
 * Piyasa rejimi sınıflandırması — kural tabanlı, deterministik.
 *
 * <p><b>Neden LLM değil:</b> rejim, aşağı akıştaki her şeyin ağırlığını değiştiriyor
 * (pozisyon boyutu, stop mesafesi, hangi göstergeye güvenileceği) ve <em>rejim değişimi
 * pahalı bir LLM turunu tetikliyor</em>. Sınıflandırmayı LLM'e yaptırmak, hem kararın
 * temelini örneklemeye açar hem de her turda ek maliyet çıkarır. Kural tabanlı olduğu
 * için ayrıca geri testte bedavaya koşuyor.
 */
public interface RegimeClassifier {

    /**
     * Göstergeler ve istatistiklerden rejimi çıkarır.
     *
     * <p>Girdiler eksikse {@link Regime.Trend#UNKNOWN} döner — eksik veriyi varsayılan
     * bir rejime doldurmaz.
     *
     * <p><b>{@code previous} neden parametre:</b> sınıflandırma histerezis kullanıyor —
     * bir rejime girmek için gereken eşik, o rejimde kalmak için gerekenden yüksek.
     * Önceki durumu alan bir alanda saklamak yerine parametre yapmak, fonksiyonu saf
     * ve deterministik tutuyor: aynı girdilerden aynı çıktı, geri testte de canlıda da.
     *
     * @param previous bir önceki sınıflandırma; ilk çağrıda {@code null}
     */
    Regime classify(IndicatorSet indicators, PriceStats stats, Regime previous);
}
