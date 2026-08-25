package com.investor.marketdata.internal;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Piyasa verisi ayarları.
 *
 * @param symbols            izlenecek semboller. Beyaz liste bilinçli: sistemin hangi
 *                           enstrümanlara dokunabileceği yapılandırmayla sınırlanır.
 * @param baseUrl            borsa REST kökü. Testnet'e yönlendirmek için de kullanılır.
 * @param weightBudget       bu eşiğin üstünde ingest yavaşlar; emir yolu için rezerv kalır.
 * @param stalenessTolerance bu süreden eski veri bayat sayılır ve ajanlar çekimser kalır.
 * @param partitionsAhead    kaç ay önceden partition açılacağı.
 */
@ConfigurationProperties(prefix = "investor.market-data")
public record MarketDataProperties(
        List<String> symbols,
        String baseUrl,
        Integer weightBudget,
        Duration stalenessTolerance,
        Integer partitionsAhead,
        Duration requestTimeout) {

    public MarketDataProperties {
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.binance.com" : baseUrl;
        weightBudget = weightBudget == null ? 800 : weightBudget;
        stalenessTolerance = stalenessTolerance == null ? Duration.ofMinutes(5) : stalenessTolerance;
        partitionsAhead = partitionsAhead == null ? 3 : partitionsAhead;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(15) : requestTimeout;
    }
}
