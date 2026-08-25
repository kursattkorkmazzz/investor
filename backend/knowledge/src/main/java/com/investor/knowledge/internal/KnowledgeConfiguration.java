package com.investor.knowledge.internal;

import java.time.Clock;

import com.investor.knowledge.MacroIngest;
import com.investor.knowledge.MacroSource;
import com.investor.knowledge.NewsExtractor;
import com.investor.knowledge.NewsFeedSource;
import com.investor.knowledge.NewsIngest;
import com.investor.ontology.OntologyStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Bilgi hattı bean tanımları. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeConfiguration {

    @Bean
    SyndicationFeedParser syndicationFeedParser() {
        return new SyndicationFeedParser();
    }

    @Bean
    NewsRepository newsRepository(JdbcClient jdbcClient) {
        return new NewsRepository(jdbcClient);
    }

    @Bean
    IngestCursors ingestCursors(JdbcClient jdbcClient) {
        return new IngestCursors(jdbcClient);
    }

    @Bean
    KnowledgeOntology knowledgeOntology(OntologyStore store) {
        return new KnowledgeOntology(store);
    }

    @Bean
    @ConditionalOnMissingBean(NewsFeedSource.class)
    NewsFeedSource newsFeedSource(SyndicationFeedParser parser, Clock clock,
                                  KnowledgeProperties properties) {
        return new HttpNewsFeedSource(properties.requestTimeout(), parser, clock);
    }

    /**
     * Kural tabanlı çıkarım — Faz 3'te LangChain4j tabanlı gerçekleme bunun yerini alacak
     * ({@code ConditionalOnMissingBean} sayesinde tanımlandığı anda devreye girer).
     */
    @Bean
    @ConditionalOnMissingBean(NewsExtractor.class)
    NewsExtractor newsExtractor(KnowledgeProperties properties) {
        return new HeuristicNewsExtractor(properties.entityKeywords());
    }

    @Bean
    NewsIngest newsIngest(NewsRepository repository, NewsFeedSource feedSource,
                          NewsExtractor extractor, KnowledgeOntology ontology, Clock clock,
                          KnowledgeProperties properties) {
        return new DefaultNewsIngest(repository, feedSource, extractor, ontology, clock,
                properties.similarityThreshold(), properties.clusterWindow(),
                properties.candidateLimit());
    }

    @Bean
    @ConditionalOnMissingBean(MacroSource.class)
    MacroSource macroSource(KnowledgeProperties properties) {
        return new FredMacroSource(properties.fredBaseUrl(), properties.fredApiKey(),
                properties.requestTimeout());
    }

    @Bean
    MacroIngest macroIngest(MacroSource source, KnowledgeOntology ontology, IngestCursors cursors,
                            Clock clock, KnowledgeProperties properties) {
        return new DefaultMacroIngest(source, ontology, cursors, clock, properties.macroSeries());
    }

    /**
     * Zamanlanmış toplama yalnızca açıkça etkinleştirildiğinde koşar; testlerde ve yerel
     * geliştirmede beklenmedik dış istek yapılmasın.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "investor.knowledge.scheduling-enabled", havingValue = "true")
    KnowledgeScheduler knowledgeScheduler(NewsIngest newsIngest, MacroIngest macroIngest,
                                          KnowledgeProperties properties) {
        return new KnowledgeScheduler(newsIngest, macroIngest, properties);
    }
}
