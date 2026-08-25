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
     */
    Regime classify(IndicatorSet indicators, PriceStats stats);
}
