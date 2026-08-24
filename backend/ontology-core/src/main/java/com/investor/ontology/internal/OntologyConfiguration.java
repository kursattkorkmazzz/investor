package com.investor.ontology.internal;

import java.time.Clock;

import javax.sql.DataSource;

import com.investor.ontology.OntologyStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.json.JsonMapper;

/** Ontoloji modülünün bean tanımları. */
@Configuration(proxyBeanMethods = false)
public class OntologyConfiguration {

    @Bean
    JdbcClient ontologyJdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    /**
     * Ontolojinin kendi JSON eşleyicisi.
     *
     * <p>Uygulamanın genel {@code ObjectMapper}'ına bilinçli olarak bağlanmıyoruz:
     * ontoloji JSON'u API cevabı değil, <em>saklanan veri</em>. Eşleyicinin davranışı
     * (özellikle sayı ve anahtar işleme) uygulamanın sunum ayarlarıyla birlikte
     * değişirse, aynı değerin iki kez farklı serileşmesi mümkün olur — bu da
     * "değişmedi" karşılaştırmasını ve denetim defterini sessizce bozar.
     */
    @Bean
    ValueCodec ontologyValueCodec() {
        return new ValueCodec(JsonMapper.builder().build());
    }

    /**
     * Şema kaydı ilk erişimde yüklenir; sonraki yenilemeleri her şema yazımı kendisi tetikler.
     * Burada yüklemiyoruz — bean oluşturma sırası Flyway migration'ından önce olabilir.
     */
    @Bean
    SchemaRegistry ontologySchemaRegistry(JdbcClient jdbcClient, ValueCodec codec) {
        return new SchemaRegistry(jdbcClient, codec);
    }

    @Bean
    QueryCompiler ontologyQueryCompiler(SchemaRegistry registry) {
        return new QueryCompiler(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    OntologyStore ontologyStore(JdbcClient jdbcClient, SchemaRegistry registry,
                                ValueCodec codec, QueryCompiler queryCompiler, Clock clock) {
        return new JdbcOntologyStore(jdbcClient, registry, codec, queryCompiler, clock);
    }
}
