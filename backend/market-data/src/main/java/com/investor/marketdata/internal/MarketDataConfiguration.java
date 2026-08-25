package com.investor.marketdata.internal;

import java.time.Clock;

import com.investor.marketdata.InstrumentCatalog;
import com.investor.marketdata.MarketDataIngest;
import com.investor.marketdata.MarketDataReader;
import com.investor.marketdata.MarketDataSource;
import com.investor.ontology.OntologyStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/** Piyasa verisi modülünün bean tanımları. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MarketDataProperties.class)
public class MarketDataConfiguration {

    @Bean
    BarWriter marketDataBarWriter(NamedParameterJdbcTemplate jdbcTemplate, JdbcClient jdbcClient) {
        return new BarWriter(jdbcTemplate, jdbcClient);
    }

    @Bean
    MarketDataReader marketDataReader(JdbcClient jdbcClient, MarketDataProperties properties) {
        return new JdbcMarketDataReader(jdbcClient, properties.stalenessTolerance());
    }

    @Bean
    InstrumentCatalog instrumentCatalog(JdbcClient jdbcClient, OntologyStore ontology, Clock clock) {
        return new JdbcInstrumentCatalog(jdbcClient, ontology, clock);
    }

    @Bean
    RollupService rollupService(JdbcClient jdbcClient, BarWriter writer) {
        return new RollupService(jdbcClient, writer);
    }

    @Bean
    IngestWatermarks ingestWatermarks(JdbcClient jdbcClient) {
        return new IngestWatermarks(jdbcClient);
    }

    @Bean
    PartitionMaintenance partitionMaintenance(JdbcClient jdbcClient, Clock clock,
                                              MarketDataProperties properties) {
        return new PartitionMaintenance(jdbcClient, clock, properties.partitionsAhead());
    }

    @Bean
    @ConditionalOnMissingBean(MarketDataSource.class)
    MarketDataSource binanceMarketDataSource(Clock clock, MarketDataProperties properties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(properties.requestTimeout())
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                        .build());
        requestFactory.setReadTimeout(properties.requestTimeout());

        RestClient http = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
        return new BinanceMarketDataSource(http, clock, properties.weightBudget(),
                BinanceMarketDataSource.Sleeper.REAL);
    }

    /**
     * Zamanlanmış toplama yalnızca açıkça etkinleştirildiğinde koşar.
     *
     * <p>Varsayılan kapalı: testler ve yerel geliştirme sırasında beklenmedik dış istek
     * yapılmasın. Üretimde {@code investor.market-data.scheduling-enabled=true}.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "investor.market-data.scheduling-enabled", havingValue = "true")
    MarketDataScheduler marketDataScheduler(InstrumentCatalog catalog, MarketDataIngest ingest,
                                            MarketDataSource source, PartitionMaintenance partitions,
                                            MarketDataProperties properties, Clock clock) {
        return new MarketDataScheduler(catalog, ingest, source, partitions, properties, clock);
    }

    @Bean
    MarketDataIngest marketDataIngest(MarketDataSource source, MarketDataReader reader,
                                      BarWriter writer, RollupService rollupService,
                                      IngestWatermarks watermarks, Clock clock) {
        return new DefaultMarketDataIngest(source, reader, writer, rollupService, watermarks, clock);
    }
}
