package com.investor.knowledge;

import java.time.Instant;

import com.investor.ontology.internal.OntologyConfiguration;
import com.investor.ontology.support.MutableClock;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Ontoloji + bilgi hattı modüllerini birlikte ayağa kaldıran test uygulaması. */
@SpringBootApplication
@Import(OntologyConfiguration.class)
public class KnowledgeTestApplication {

    @Bean
    MutableClock clock() {
        return new MutableClock(Instant.parse("2026-08-20T09:00:00Z"));
    }
}
