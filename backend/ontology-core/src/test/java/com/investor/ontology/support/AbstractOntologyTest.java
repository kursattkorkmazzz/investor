package com.investor.ontology.support;

import java.time.Instant;

import com.investor.ontology.OntologyStore;
import com.investor.ontology.model.ActorType;
import com.investor.ontology.model.CommitContext;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = OntologyTestApplication.class)
public abstract class AbstractOntologyTest {

    protected static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    protected OntologyStore store;

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
        // Denetim defteri append-only olduğu için trigger'ı geçici olarak devre dışı bırakıyoruz.
        // Üretimde böyle bir yol yok; testin temiz başlaması için tek istisna.
        jdbc.sql("ALTER TABLE ontology_change_log DISABLE TRIGGER USER").update();
        try {
            for (String table : new String[]{
                    "ontology_change_log", "property_value", "link_instance", "object_current",
                    "object_instance", "property_type", "link_type", "object_type_version",
                    "object_type", "ontology_commit", "data_source"}) {
                jdbc.sql("DELETE FROM " + table).update();
            }
        } finally {
            jdbc.sql("ALTER TABLE ontology_change_log ENABLE TRIGGER USER").update();
        }
        reloadSchemaCache();
    }

    /** Şema önbelleği uygulama ömrü boyunca yaşadığı için test arası yeniden yüklenmeli. */
    private void reloadSchemaCache() {
        store.refreshSchema();
    }

    protected CommitContext ctx(String reason) {
        return new CommitContext(ActorType.SYSTEM, "test", reason, null, null, null, null);
    }
}
