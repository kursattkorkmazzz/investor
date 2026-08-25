package com.investor.marketdata.support;

import java.math.BigDecimal;
import java.time.Instant;

import com.investor.marketdata.InstrumentCatalog;
import com.investor.marketdata.MarketDataReader;
import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.InstrumentSpec;
import com.investor.marketdata.model.InstrumentSpec.InstrumentStatus;
import com.investor.ontology.OntologyStore;
import com.investor.ontology.support.MutableClock;
import com.investor.ontology.support.PostgresResource;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = com.investor.marketdata.MarketDataTestApplication.class)
public abstract class AbstractMarketDataTest {

    /** Ay ortası: partition sınırlarına denk gelmeyen, hizalanmış bir başlangıç. */
    protected static final Instant T0 = Instant.parse("2026-03-15T12:00:00Z");

    @Autowired
    protected MarketDataReader reader;

    @Autowired
    protected InstrumentCatalog catalog;

    @Autowired
    protected OntologyStore ontology;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected MutableClock clock;

    protected InstrumentRef btcusdt;

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
                    "ohlcv", "derivative_metric", "ingest_watermark", "instrument",
                    "ontology_change_log", "property_value", "link_instance", "object_current",
                    "object_instance", "property_type", "link_type", "object_type_version",
                    "object_type", "ontology_commit", "data_source"}) {
                jdbc.sql("DELETE FROM " + table).update();
            }
        } finally {
            jdbc.sql("ALTER TABLE ontology_change_log ENABLE TRIGGER USER").update();
        }
        ontology.refreshSchema();
        btcusdt = catalog.register(new InstrumentSpec(
                "BINANCE", "BTCUSDT", "BTC", "USDT", InstrumentStatus.TRADING,
                new BigDecimal("0.01"), new BigDecimal("0.00001"), new BigDecimal("5")));
    }
}
