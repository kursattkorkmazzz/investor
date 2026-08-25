package com.investor.llm;

import java.time.Instant;

import com.investor.ontology.support.MutableClock;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** LLM modülünü tek başına ayağa kaldıran test uygulaması. */
@SpringBootApplication
public class LlmTestApplication {

    @Bean
    MutableClock clock() {
        return new MutableClock(Instant.parse("2026-08-20T09:00:00Z"));
    }
}
