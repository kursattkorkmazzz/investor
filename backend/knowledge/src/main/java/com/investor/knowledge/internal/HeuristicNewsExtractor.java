package com.investor.knowledge.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.investor.knowledge.NewsExtractor;
import com.investor.knowledge.model.EventType;
import com.investor.knowledge.model.NewsAnalysis;
import com.investor.knowledge.model.RawNewsItem;

/**
 * Kural tabanlı çıkarım — LLM gelene kadarki varsayılan.
 *
 * <p>Amacı iyi çıkarım yapmak değil, hattın LLM olmadan da uçtan uca çalışmasını
 * sağlamak. Böylece ingest'in doğruluğu (tekilleştirme, zaman damgaları, ontoloji
 * yazımı) model kalitesinden bağımsız test edilebiliyor.
 *
 * <p>Ürettiği önem skoru bilinçli olarak düşük tavanlı: kural tabanlı bir çıkarımın
 * yüksek güvenle konuşması, kalibrasyonu baştan bozardı. Faz 3'te LangChain4j tabanlı
 * gerçekleme bunun yerini alacak.
 */
class HeuristicNewsExtractor implements NewsExtractor {

    /** Kural tabanlı çıkarımın üretebileceği azami önem. */
    private static final double MATERIALITY_CEILING = 0.5;

    private static final Map<EventType, Set<String>> EVENT_KEYWORDS = Map.of(
            EventType.REGULATORY, Set.of("sec", "regulator", "lawsuit", "court", "ban", "approval",
                    "compliance", "legislation"),
            EventType.MACRO, Set.of("fed", "inflation", "cpi", "rate", "fomc", "gdp", "unemployment",
                    "treasury", "yield"),
            EventType.SECURITY_INCIDENT, Set.of("hack", "exploit", "breach", "stolen", "vulnerability",
                    "attack"),
            EventType.LISTING, Set.of("listing", "listed", "delist", "trading pair"),
            EventType.PROTOCOL, Set.of("upgrade", "fork", "mainnet", "testnet", "halving", "merge"),
            EventType.FUNDING, Set.of("raise", "funding", "series", "investment", "valuation"),
            EventType.PARTNERSHIP, Set.of("partnership", "partners", "collaboration", "integration"));

    private static final Set<String> POSITIVE = Set.of("surge", "rally", "gain", "approval", "approved",
            "record", "growth", "adoption", "bullish", "upgrade", "partnership", "inflow");
    private static final Set<String> NEGATIVE = Set.of("crash", "plunge", "hack", "exploit", "ban",
            "lawsuit", "rejected", "delay", "delayed", "bearish", "outflow", "liquidation", "fraud");
    private static final Set<String> SPECULATIVE = Set.of("could", "might", "rumor", "rumour",
            "reportedly", "speculation", "may", "expected");

    private final Map<String, String> entityKeywords;

    HeuristicNewsExtractor(Map<String, String> entityKeywords) {
        this.entityKeywords = Map.copyOf(entityKeywords);
    }

    @Override
    public String extractorId() {
        return "heuristic-v1";
    }

    @Override
    public NewsAnalysis analyze(RawNewsItem item) {
        Set<String> tokens = TextNormalizer.tokens(item.similarityText());
        String normalized = TextNormalizer.normalize(item.similarityText());

        EventType eventType = classify(tokens);
        double sentiment = sentiment(tokens);
        boolean speculation = tokens.stream().anyMatch(SPECULATIVE::contains);
        double materiality = materiality(eventType, sentiment, speculation);

        return new NewsAnalysis(sentiment, materiality, eventType,
                entities(normalized), item.summary(), speculation,
                NewsAnalysis.TimeHorizon.DAYS, extractorId());
    }

    private static EventType classify(Set<String> tokens) {
        EventType best = EventType.OTHER;
        int bestScore = 0;
        for (Map.Entry<EventType, Set<String>> entry : EVENT_KEYWORDS.entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue()) {
                if (tokens.contains(keyword)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = entry.getKey();
            }
        }
        return best;
    }

    private static double sentiment(Set<String> tokens) {
        int positive = 0;
        int negative = 0;
        for (String token : tokens) {
            if (POSITIVE.contains(token)) {
                positive++;
            }
            if (NEGATIVE.contains(token)) {
                negative++;
            }
        }
        if (positive == 0 && negative == 0) {
            return 0;
        }
        double raw = (double) (positive - negative) / (positive + negative);
        return Math.max(-1, Math.min(1, raw));
    }

    private static double materiality(EventType eventType, double sentiment, boolean speculation) {
        double base = switch (eventType) {
            case REGULATORY, MACRO, SECURITY_INCIDENT -> 0.45;
            case PROTOCOL, LISTING -> 0.35;
            case FUNDING, PARTNERSHIP, MARKET_MOVE -> 0.25;
            case OPINION, OTHER -> 0.15;
        };
        double withTone = base + Math.abs(sentiment) * 0.1;
        double withSpeculation = speculation ? withTone * 0.6 : withTone;
        return Math.min(MATERIALITY_CEILING, withSpeculation);
    }

    /** Anahtar kelimeden varlığa eşleme; yapılandırmadan gelir. */
    private List<String> entities(String normalizedText) {
        Set<String> found = new LinkedHashSet<>();
        String padded = " " + normalizedText + " ";
        entityKeywords.forEach((keyword, externalId) -> {
            if (padded.contains(" " + TextNormalizer.normalize(keyword) + " ")) {
                found.add(externalId);
            }
        });
        return new ArrayList<>(found);
    }
}
