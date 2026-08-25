package com.investor.knowledge.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.investor.knowledge.model.MacroPoint;
import com.investor.knowledge.model.MacroSeriesSpec;
import com.investor.knowledge.model.NewsAnalysis;
import com.investor.knowledge.model.RawNewsItem;
import com.investor.ontology.OntologyStore;
import com.investor.ontology.model.ActorType;
import com.investor.ontology.model.CommitContext;
import com.investor.ontology.model.DataType;
import com.investor.ontology.model.LinkCardinality;
import com.investor.ontology.model.NewLinkType;
import com.investor.ontology.model.NewObjectType;
import com.investor.ontology.model.NewPropertyType;
import com.investor.ontology.model.ObjectRef;
import com.investor.ontology.model.PropertyHistoryEntry;
import com.investor.ontology.model.Value;
import com.investor.ontology.model.Values;

import org.springframework.transaction.annotation.Transactional;

/**
 * Bilgi hattının ontolojiye yazan yüzü.
 *
 * <p>Modül kendi ihtiyaç duyduğu tipleri kendisi kurar — şema çalışma zamanında
 * tanımlanabildiği için ayrı bir hazırlık adımı gerekmiyor. Tip zaten varsa dokunulmaz;
 * başka bir modül aynı tipi daha önce kurmuş olabilir.
 */
class KnowledgeOntology {

    static final String NEWS_ARTICLE = "NewsArticle";
    static final String ASSET = "Asset";
    static final String MACRO_INDICATOR = "MacroIndicator";
    static final String MACRO_OBSERVATION = "MacroObservation";
    static final String LINK_MENTIONS = "MENTIONS";
    static final String LINK_OF = "OF";

    private final OntologyStore store;

    KnowledgeOntology(OntologyStore store) {
        this.store = store;
    }

    @Transactional
    void ensureTypes() {
        if (store.objectType(ASSET).isEmpty()) {
            store.defineObjectType(NewObjectType.of(ASSET, "Varlık"), ctx("varlık tipi"));
            property(ASSET, "name", "Ad", DataType.STRING, true);
            property(ASSET, "symbol", "Sembol", DataType.STRING, false);
        }

        if (store.objectType(NEWS_ARTICLE).isEmpty()) {
            store.defineObjectType(NewObjectType.of(NEWS_ARTICLE, "Haber"), ctx("haber tipi"));
            property(NEWS_ARTICLE, "title", "Başlık", DataType.TEXT, true);
            property(NEWS_ARTICLE, "url", "Adres", DataType.STRING, false);
            property(NEWS_ARTICLE, "summary", "Özet", DataType.TEXT, false);
            property(NEWS_ARTICLE, "publishedAt", "Yayın Zamanı", DataType.TIMESTAMP, false);
            property(NEWS_ARTICLE, "eventType", "Olay Türü", DataType.STRING, false);
            property(NEWS_ARTICLE, "timeHorizon", "Etki Ufku", DataType.STRING, false);
            // Yön ve önem ayrı: "çok olumsuz ama önemsiz" ile "hafif olumsuz ama çok önemli"
            // farklı ağırlık taşımalı.
            decimal(NEWS_ARTICLE, "sentiment", "Duygu", "-1..1");
            decimal(NEWS_ARTICLE, "materiality", "Önem", "0..1");
            property(NEWS_ARTICLE, "sourceCount", "Kaynak Sayısı", DataType.INTEGER, false);
            property(NEWS_ARTICLE, "speculation", "Spekülatif", DataType.BOOLEAN, false);
        }

        if (store.objectType(MACRO_INDICATOR).isEmpty()) {
            store.defineObjectType(NewObjectType.of(MACRO_INDICATOR, "Makro Gösterge"),
                    ctx("makro gösterge tipi"));
            property(MACRO_INDICATOR, "code", "Kod", DataType.STRING, true);
            property(MACRO_INDICATOR, "displayName", "Ad", DataType.STRING, false);
            property(MACRO_INDICATOR, "unit", "Birim", DataType.STRING, false);
            property(MACRO_INDICATOR, "frequency", "Sıklık", DataType.STRING, false);
            property(MACRO_INDICATOR, "source", "Kaynak", DataType.STRING, false);
        }

        if (store.objectType(MACRO_OBSERVATION).isEmpty()) {
            store.defineObjectType(NewObjectType.of(MACRO_OBSERVATION, "Makro Gözlem"),
                    ctx("makro gözlem tipi"));
            property(MACRO_OBSERVATION, "label", "Etiket", DataType.STRING, true);
            property(MACRO_OBSERVATION, "period", "Dönem", DataType.DATE, false);
            property(MACRO_OBSERVATION, "value", "Değer", DataType.DECIMAL, false);
            property(MACRO_OBSERVATION, "revision", "Düzeltme", DataType.BOOLEAN, false);
        }

        ensureLink(LINK_MENTIONS, "Bahsettiği varlıklar", "MENTIONED_IN", "Bahseden haberler",
                NEWS_ARTICLE, ASSET, LinkCardinality.MANY_TO_MANY);
        ensureLink(LINK_OF, "Göstergesi", "OBSERVATIONS", "Gözlemleri",
                MACRO_OBSERVATION, MACRO_INDICATOR, LinkCardinality.MANY_TO_ONE);
    }

    // ------------------------------------------------------------------ haber

    /**
     * Küme için ontoloji nesnesini oluşturur.
     *
     * <p>{@code validFrom} yayın zamanı, kayıt zamanı ise sistemin saatinden gelir —
     * ikisinin ayrı olması, "haberi o an gerçekten görmüş müydük" sorusunun
     * cevaplanabilmesini sağlar.
     */
    @Transactional
    ObjectRef writeArticle(String externalId, RawNewsItem item, NewsAnalysis analysis,
                           int sourceCount, java.util.UUID sourceId) {
        // Aktör kimliğine çıkarıcı da giriyor: hangi satırın LLM'den, hangisinin kural
        // tabanlı yedekten geldiği sonradan sorulabilsin. Kalibrasyon bu ayrımı gerektirecek.
        CommitContext ctx = CommitContext.ingestor("news-ingest/" + analysis.extractorId(),
                "haber kaydı", sourceId);
        CommitContext bound = store.openCommit(ctx);
        ObjectRef article = store.findOrCreate(NEWS_ARTICLE, externalId, bound);

        Map<String, Value> values = new LinkedHashMap<>();
        values.put("title", Values.text(item.title()));
        values.put("url", Values.text(item.url()));
        values.put("publishedAt", Values.timestamp(item.publishedAt()));
        values.put("eventType", Values.text(analysis.eventType().name()));
        values.put("timeHorizon", Values.text(analysis.timeHorizon().name()));
        values.put("sentiment", Values.number(BigDecimal.valueOf(analysis.sentiment())));
        values.put("materiality", Values.number(BigDecimal.valueOf(analysis.materiality())));
        values.put("sourceCount", Values.number(sourceCount));
        values.put("speculation", Values.bool(analysis.speculation()));
        if (analysis.summary() != null && !analysis.summary().isBlank()) {
            values.put("summary", Values.text(analysis.summary()));
        }

        store.setProperties(article, values, item.publishedAt(), bound);
        linkEntities(article, analysis.entities(), item.publishedAt(), bound);
        return article;
    }

    /** Kümeye yeni kaynak katıldığında yalnızca sayaç değişir; yeni haber nesnesi doğmaz. */
    @Transactional
    void updateSourceCount(ObjectRef article, int sourceCount, Instant at) {
        store.setProperty(article, "sourceCount", Values.number(sourceCount), at,
                CommitContext.ingestor("news-ingest", "kaynak sayısı güncellendi", null));
    }

    private void linkEntities(ObjectRef article, List<String> entities, Instant at, CommitContext ctx) {
        for (String externalId : entities) {
            ObjectRef asset = store.findOrCreate(ASSET, externalId, ctx);
            store.setProperty(asset, "name", Values.text(displayNameOf(externalId)), at, ctx);
            store.link(article, LINK_MENTIONS, asset, null, at, ctx);
        }
    }

    private static String displayNameOf(String externalId) {
        int separator = externalId.indexOf(':');
        return separator < 0 ? externalId : externalId.substring(separator + 1);
    }

    // ------------------------------------------------------------------ makro

    @Transactional
    ObjectRef writeIndicator(MacroSeriesSpec spec, String sourceName, Instant at) {
        CommitContext ctx = CommitContext.ingestor("macro-ingest", "gösterge tanımı", null)
                .withRecordedAt(at);
        CommitContext bound = store.openCommit(ctx);
        ObjectRef indicator = store.findOrCreate(MACRO_INDICATOR, spec.externalId(), bound);

        Map<String, Value> values = new LinkedHashMap<>();
        values.put("code", Values.text(spec.code()));
        values.put("source", Values.text(sourceName));
        if (spec.displayName() != null) {
            values.put("displayName", Values.text(spec.displayName()));
        }
        if (spec.unit() != null) {
            values.put("unit", Values.text(spec.unit()));
        }
        if (spec.frequency() != null) {
            values.put("frequency", Values.text(spec.frequency()));
        }
        store.setProperties(indicator, values, at, bound);
        return indicator;
    }

    /**
     * Bir gözlem sürümünü yazar.
     *
     * <p>Geçerlilik aralığı, rakamın <em>resmî rakam olduğu</em> dönemdir: bir revizyon
     * öncekini ezmez, kapatır. Böylece "15 Ağustos'ta hangi CPI'ı görüyorduk" sorusu
     * revizyondan sonra da doğru cevaplanır.
     *
     * @return yeni sürüm yazıldıysa {@code true}; zaten kayıtlıysa {@code false}
     */
    @Transactional
    boolean writeObservation(ObjectRef indicator, MacroPoint point, java.util.UUID sourceId) {
        // Kayıt zamanı = yayın (vintage) zamanı. Bir makro rakam, yayınlandığı anda
        // dünyaya açılır; onu ne zaman çektiğimiz bilgi durumunu değiştirmez. Geriye
        // dönük yüklenen bir seri, bu olmadan geçmiş sorgularda hiç görünmezdi.
        CommitContext ctx = CommitContext.ingestor("macro-ingest",
                        point.revision() ? "revize edilmiş gözlem" : "ilk yayın", sourceId)
                .withRecordedAt(point.vintageFrom());
        CommitContext bound = store.openCommit(ctx);
        ObjectRef observation = store.findOrCreate(MACRO_OBSERVATION, point.externalId(), bound);

        if (vintageAlreadyRecorded(observation, point)) {
            return false;
        }

        store.setProperties(observation, Map.of(
                "label", Values.text(point.seriesCode() + " " + point.period()),
                "period", Values.date(point.period())), point.vintageFrom(), bound);

        if (point.vintageTo() == null) {
            store.setProperty(observation, "value", Values.number(point.value()),
                    point.vintageFrom(), bound);
            store.setProperty(observation, "revision", Values.bool(point.revision()),
                    point.vintageFrom(), bound);
        } else {
            store.setProperty(observation, "value", Values.number(point.value()),
                    point.vintageFrom(), point.vintageTo(), bound);
            store.setProperty(observation, "revision", Values.bool(point.revision()),
                    point.vintageFrom(), point.vintageTo(), bound);
        }

        store.link(observation, LINK_OF, indicator, null, point.vintageFrom(), bound);
        return true;
    }

    /**
     * Bu sürüm daha önce yazıldı mı.
     *
     * <p>Ingest yeniden koştuğunda aynı sürümü tekrar yazmaya kalkmak, {@code EXCLUDE}
     * kısıtına takılıp hata verirdi. Idempotentlik burada açıkça sağlanıyor.
     */
    private boolean vintageAlreadyRecorded(ObjectRef observation, MacroPoint point) {
        List<PropertyHistoryEntry> history = store.history(observation, "value");
        return history.stream()
                .filter(entry -> !entry.isRetracted())
                .anyMatch(entry -> entry.validFrom().equals(point.vintageFrom()));
    }

    // ------------------------------------------------------------------

    private void property(String type, String apiName, String displayName, DataType dataType, boolean title) {
        NewPropertyType spec = NewPropertyType.of(apiName, displayName, dataType);
        store.defineProperty(type, title ? spec.asTitle() : spec, ctx("alan: " + apiName));
    }

    private void decimal(String type, String apiName, String displayName, String range) {
        String[] bounds = range.split("\\.\\.");
        store.defineProperty(type, NewPropertyType.of(apiName, displayName, DataType.DECIMAL)
                .withConstraints(Map.of("min", new BigDecimal(bounds[0]), "max", new BigDecimal(bounds[1]))),
                ctx("alan: " + apiName));
    }

    private void ensureLink(String apiName, String displayName, String reverseApiName,
                            String reverseDisplayName, String fromType, String toType,
                            LinkCardinality cardinality) {
        boolean exists = store.linkTypes().stream().anyMatch(lt -> lt.apiName().equals(apiName));
        if (!exists) {
            store.defineLinkType(NewLinkType.of(apiName, displayName, reverseApiName,
                    reverseDisplayName, fromType, toType, cardinality), ctx("ilişki: " + apiName));
        }
    }

    private static CommitContext ctx(String reason) {
        return new CommitContext(ActorType.SYSTEM, "knowledge", reason, null, null, null, null, null);
    }
}
