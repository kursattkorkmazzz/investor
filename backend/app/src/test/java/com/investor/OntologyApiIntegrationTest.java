package com.investor;

import java.time.Clock;
import java.time.Instant;

import com.investor.ontology.support.MutableClock;
import com.investor.ontology.support.PostgresResource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP yolunun uçtan uca doğrulaması: gerçek uygulama, gerçek PostgreSQL, gerçek istekler.
 *
 * <p>Burada kanıtlanan şey, ontoloji testlerinin kanıtladığından farklı: şemanın çalışma
 * zamanında HTTP üzerinden tanımlanabildiği, ve bitemporal okumanın API'ye kadar
 * bozulmadan çıktığı.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Ontoloji HTTP API")
class OntologyApiIntegrationTest {

    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant JUN = Instant.parse("2026-06-01T00:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(JAN);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Clock clock;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresResource db = PostgresResource.get();
        registry.add("spring.datasource.url", db::url);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
    }

    @BeforeEach
    void reset() {
        ((MutableClock) clock).setTo(JAN);
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
    }

    @Test
    @DisplayName("şema tanımından bitemporal okumaya kadar tam akış")
    void fullRoundTrip() throws Exception {
        // --- 1. Şema çalışma zamanında tanımlanıyor: deploy yok, migration yok
        mvc.perform(post("/api/ontology/types").contentType(MediaType.APPLICATION_JSON).content("""
                {"apiName":"CryptoAsset","displayName":"Kripto Varlık","reason":"ilk şema"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiName").value("CryptoAsset"));

        mvc.perform(post("/api/ontology/types/CryptoAsset/properties")
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"apiName":"name","displayName":"Ad","dataType":"STRING","title":true}
                """))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/ontology/types/CryptoAsset/properties")
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"apiName":"marketCap","displayName":"Piyasa Değeri","dataType":"DECIMAL","unit":"USD"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.properties.length()").value(2));

        // --- 2. Nesne oluşturuluyor
        String created = mvc.perform(post("/api/ontology/objects")
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"typeApiName":"CryptoAsset","externalId":"BINANCE:BTC",
                 "values":{"name":"Bitcoin","marketCap":"1300000000000"},
                 "validFrom":"2026-01-01T00:00:00Z","reason":"ilk kayıt"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Bitcoin"))
                // Ondalık değer metin olarak dönmeli — JS'in double'ı büyük sayıyı yuvarlar
                .andExpect(jsonPath("$.data.marketCap").value("1300000000000"))
                .andReturn().getResponse().getContentAsString();

        String id = created.replaceAll(".*\"objectId\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        // --- 3. Haziran'da değer güncelleniyor
        ((MutableClock) clock).setTo(JUN);
        mvc.perform(patch("/api/ontology/objects/" + id)
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"values":{"marketCap":"1450000000000"},
                 "validFrom":"2026-06-01T00:00:00Z","reason":"haziran güncellemesi"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.marketCap").value("1450000000000"));

        // --- 4. Güncel vs geçmiş okuma
        mvc.perform(get("/api/ontology/objects/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.marketCap").value("1450000000000"));

        mvc.perform(get("/api/ontology/objects/" + id).param("asOf", "2026-03-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.marketCap").value("1300000000000"))
                .andExpect(jsonPath("$.knowledgeTime").exists());

        // --- 5. Geçmiş: iki kayıt da duruyor
        mvc.perform(get("/api/ontology/objects/" + id + "/history/marketCap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].value").value("1450000000000"))
                .andExpect(jsonPath("$[0].reason").value("haziran güncellemesi"))
                .andExpect(jsonPath("$[1].value").value("1300000000000"))
                .andExpect(jsonPath("$[1].validTo").value("2026-06-01T00:00:00Z"));

        // --- 6. Dinamik sorgu
        mvc.perform(post("/api/ontology/query").contentType(MediaType.APPLICATION_JSON).content("""
                {"type":"CryptoAsset","where":[{"field":"marketCap","op":"GT","value":1000000000000}]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.objects[0].externalId").value("BINANCE:BTC"));

        // Aynı sorgu geçmişte de aynı sonucu vermeli
        mvc.perform(post("/api/ontology/query").contentType(MediaType.APPLICATION_JSON).content("""
                {"type":"CryptoAsset","asOf":"2026-03-01T00:00:00Z",
                 "where":[{"field":"marketCap","op":"GT","value":1400000000000}]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        // --- 7. Silme soft: geçmiş sorgusunda hâlâ var
        mvc.perform(delete("/api/ontology/objects/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/ontology/objects/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/ontology/objects/" + id).param("asOf", "2026-03-01T00:00:00Z"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("hatalar anlamlı HTTP durumlarına çevrilir")
    void errorsMapToMeaningfulStatuses() throws Exception {
        mvc.perform(post("/api/ontology/types").contentType(MediaType.APPLICATION_JSON).content("""
                {"apiName":"CryptoAsset","displayName":"Kripto Varlık"}
                """)).andExpect(status().isCreated());
        mvc.perform(post("/api/ontology/types/CryptoAsset/properties")
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"apiName":"name","displayName":"Ad","dataType":"STRING","title":true}
                """)).andExpect(status().isCreated());

        String created = mvc.perform(post("/api/ontology/objects")
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"typeApiName":"CryptoAsset","externalId":"BINANCE:BTC","values":{"name":"Bitcoin"},
                 "validFrom":"2026-03-01T00:00:00Z"}
                """)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = created.replaceAll(".*\"objectId\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        // Bilinmeyen tip / nesne -> 404
        mvc.perform(get("/api/ontology/types/YokBoyleTip")).andExpect(status().isNotFound());
        mvc.perform(get("/api/ontology/objects/00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isNotFound());

        // Bilinmeyen alan -> 404 (şemada yok)
        mvc.perform(patch("/api/ontology/objects/" + id)
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"values":{"olmayanAlan":"x"}}
                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Bulunamadı"));

        // Aynı external_id -> 409
        mvc.perform(post("/api/ontology/objects").contentType(MediaType.APPLICATION_JSON).content("""
                {"typeApiName":"CryptoAsset","externalId":"BINANCE:BTC","values":{"name":"Bitcoin"}}
                """))
                .andExpect(status().isConflict());

        // Geçmişe çakışan yazma -> 409 + nasıl düzeltileceğine dair ipucu
        mvc.perform(patch("/api/ontology/objects/" + id)
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"values":{"name":"Bitcoin Eski"},"validFrom":"2026-01-01T00:00:00Z"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Zaman çakışması"))
                .andExpect(jsonPath("$.hint").exists());
    }
}
