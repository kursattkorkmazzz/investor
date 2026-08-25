package com.investor.knowledge.support;

import java.time.Instant;

import com.investor.knowledge.KnowledgeTestApplication;
import com.investor.knowledge.NewsIngest;
import com.investor.ontology.OntologyStore;
import com.investor.ontology.support.MutableClock;
import com.investor.ontology.support.PostgresResource;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = KnowledgeTestApplication.class)
public abstract class AbstractKnowledgeTest {

    protected static final Instant T0 = Instant.parse("2026-08-20T09:00:00Z");

    @Autowired
    protected OntologyStore ontology;

    @Autowired
    protected NewsIngest newsIngest;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected MutableClock clock;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresResource db = PostgresResource.get();
        registry.add("spring.datasource.url", db::url);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
    }

    @BeforeEach
    void resetDatabase() {
        clock.setTo(T0);
        jdbc.sql("ALTER TABLE ontology_change_log DISABLE TRIGGER USER").update();
        try {
            for (String table : new String[]{
                    "news_item", "news_cluster", "news_feed", "ingest_cursor",
                    "ontology_change_log", "property_value", "link_instance", "object_current",
                    "object_instance", "property_type", "link_type", "object_type_version",
                    "object_type", "ontology_commit", "data_source"}) {
                jdbc.sql("DELETE FROM " + table).update();
            }
        } finally {
            jdbc.sql("ALTER TABLE ontology_change_log ENABLE TRIGGER USER").update();
        }
        ontology.refreshSchema();
    }
}
