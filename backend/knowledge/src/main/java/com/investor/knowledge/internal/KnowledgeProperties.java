package com.investor.knowledge.internal;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bilgi hattı ayarları.
 *
 * @param similarityThreshold iki haberin aynı konu sayılması için gereken Jaccard benzerliği.
 *                            Yüksek tutmak farklı kaynakları ayrı konu sayar (önemi hacimle
 *                            karıştırır), düşük tutmak farklı konuları birleştirir. Varsayılan
 *                            ölçülmüş bir değer değil — kalibrasyon için işaretlenmiş bir
 *                            örneklem gerekiyor.
 * @param clusterWindow       kümeleme adaylarının arandığı zaman penceresi
 * @param entityKeywords      anahtar kelime → ontoloji varlık kimliği; kural tabanlı
 *                            çıkarımın varlık eşlemesi. LLM gelince yerini alacak.
 * @param fredApiKey          boşsa makro toplama devre dışı kalır
 */
@ConfigurationProperties(prefix = "investor.knowledge")
public record KnowledgeProperties(
        Double similarityThreshold,
        Duration clusterWindow,
        Integer candidateLimit,
        Duration requestTimeout,
        List<String> macroSeries,
        String fredBaseUrl,
        String fredApiKey,
        Map<String, String> entityKeywords) {

    public KnowledgeProperties {
        similarityThreshold = similarityThreshold == null ? 0.45 : similarityThreshold;
        clusterWindow = clusterWindow == null ? Duration.ofHours(72) : clusterWindow;
        candidateLimit = candidateLimit == null ? 500 : candidateLimit;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(20) : requestTimeout;
        macroSeries = macroSeries == null ? List.of() : List.copyOf(macroSeries);
        fredBaseUrl = fredBaseUrl == null || fredBaseUrl.isBlank()
                ? "https://api.stlouisfed.org" : fredBaseUrl;
        fredApiKey = fredApiKey == null ? "" : fredApiKey;
        entityKeywords = entityKeywords == null ? Map.of() : Map.copyOf(entityKeywords);
    }

    public boolean macroEnabled() {
        return !fredApiKey.isBlank() && !macroSeries.isEmpty();
    }
}
