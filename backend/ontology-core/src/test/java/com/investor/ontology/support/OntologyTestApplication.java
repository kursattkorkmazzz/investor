package com.investor.ontology.support;

import java.time.Instant;

import com.investor.ontology.internal.OntologyConfiguration;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Ontoloji modülünü tek başına ayağa kaldıran test uygulaması. */
@SpringBootApplication
@Import(OntologyConfiguration.class)
public class OntologyTestApplication {

    /** Sabit başlangıçlı, testlerin ileri sarabildiği saat. */
    @Bean
    MutableClock clock() {
        return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    }
}
