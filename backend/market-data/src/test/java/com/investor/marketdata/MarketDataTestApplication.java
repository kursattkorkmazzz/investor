package com.investor.marketdata;

import java.time.Instant;

import com.investor.ontology.internal.OntologyConfiguration;
import com.investor.ontology.support.MutableClock;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Ontoloji + piyasa verisi modüllerini birlikte ayağa kaldıran test uygulaması.
 *
 * <p>Paket kökü {@code com.investor.marketdata}: bileşen taraması modülün {@code internal}
 * paketini de kapsasın ki test kaynaklarındaki {@code MarketDataTestAccess} bulunabilsin.
 */
@SpringBootApplication
@Import(OntologyConfiguration.class)
public class MarketDataTestApplication {

    /** Sabit başlangıç: mum zamanları ve is_final kararları saate bağlı, kontrol edilebilir olmalı. */
    @Bean
    MutableClock clock() {
        return new MutableClock(Instant.parse("2026-03-15T12:00:00Z"));
    }
}
