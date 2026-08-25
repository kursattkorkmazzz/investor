package com.investor.knowledge;

import com.investor.knowledge.model.NewsAnalysis;
import com.investor.knowledge.model.RawNewsItem;

/**
 * Haber metninden yapılandırılmış çıkarım.
 *
 * <p>Bu port, Faz 3'te LangChain4j üzerinden bir LLM ile gerçeklenecek. Faz 2'de yerinde
 * kural tabanlı bir varsayılan var: hattın LLM olmadan da uçtan uca çalışması, ingest'in
 * doğruluğunu model kalitesinden ayrı test edebilmeyi sağlıyor.
 *
 * <p><b>Güvenlik:</b> gelen metin düşman girdisidir. Bir haber gövdesi "önceki talimatları
 * unut" yazabilir. LLM gerçeklemesi metni veri olarak sınırlandırmalı, çıktıyı şemaya
 * zorlamalı ve hiçbir koşulda bu çıkarımın emir üretmesine izin verilmemelidir.
 */
public interface NewsExtractor {

    NewsAnalysis analyze(RawNewsItem item);

    /** Çıkarımı yapan bileşenin kimliği — ontolojide köken olarak kaydedilir. */
    String extractorId();
}
