package com.investor.marketdata.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.investor.marketdata.InstrumentCatalog;
import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.InstrumentSpec;
import com.investor.ontology.OntologyStore;
import com.investor.ontology.model.ActorType;
import com.investor.ontology.model.CommitContext;
import com.investor.ontology.model.ObjectRef;
import com.investor.ontology.model.Values;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enstrüman kaydı.
 *
 * <p>Her enstrüman iki yerde yaşar: piyasa verisi tablolarında (kimlik ve emir kuralları)
 * ve ontolojide (bilgi katmanının bağlanabileceği bir nesne olarak). İkisi tek
 * transaction'da yazılır — yarım kayıt, hangi tarafa bakıldığına göre farklı cevap
 * veren bir sistem demektir.
 */
class JdbcInstrumentCatalog implements InstrumentCatalog {

    /** Ontolojide enstrümanları temsil eden tip. Yoksa ilk kayıtta oluşturulur. */
    static final String INSTRUMENT_TYPE = "Instrument";

    private final JdbcClient jdbc;
    private final OntologyStore ontology;
    private final java.time.Clock clock;

    JdbcInstrumentCatalog(JdbcClient jdbc, OntologyStore ontology, java.time.Clock clock) {
        this.jdbc = jdbc;
        this.ontology = ontology;
        this.clock = clock;
    }

    @Override
    public Optional<InstrumentRef> find(String exchange, String symbol) {
        return jdbc.sql("""
                SELECT id, object_id, exchange, symbol FROM instrument
                 WHERE exchange = :exchange AND symbol = :symbol
                """)
                .param("exchange", exchange)
                .param("symbol", symbol)
                .query((rs, n) -> new InstrumentRef(rs.getLong("id"),
                        UUID.fromString(rs.getString("object_id")),
                        rs.getString("exchange"), rs.getString("symbol")))
                .optional();
    }

    @Override
    public List<InstrumentRef> all() {
        return jdbc.sql("SELECT id, object_id, exchange, symbol FROM instrument ORDER BY exchange, symbol")
                .query((rs, n) -> new InstrumentRef(rs.getLong("id"),
                        UUID.fromString(rs.getString("object_id")),
                        rs.getString("exchange"), rs.getString("symbol")))
                .list();
    }

    @Override
    @Transactional
    public InstrumentRef register(InstrumentSpec spec) {
        ensureOntologyType();
        Instant now = clock.instant();
        String externalId = spec.exchange() + ":" + spec.symbol();

        CommitContext ctx = CommitContext.ingestor("instrument-catalog",
                "borsa enstrüman tanımı", null);
        ObjectRef object = ontology.findOrCreate(INSTRUMENT_TYPE, externalId, ctx);
        ontology.setProperties(object, java.util.Map.of(
                "symbol", Values.text(spec.symbol()),
                "exchange", Values.text(spec.exchange()),
                "baseAsset", Values.text(spec.baseAsset()),
                "quoteAsset", Values.text(spec.quoteAsset()),
                "status", Values.text(spec.status().name())), now, ctx);

        jdbc.sql("""
                INSERT INTO instrument (object_id, exchange, symbol, base_asset, quote_asset,
                                        status, tick_size, step_size, min_notional)
                VALUES (:objectId, :exchange, :symbol, :baseAsset, :quoteAsset,
                        :status, :tickSize, :stepSize, :minNotional)
                ON CONFLICT (exchange, symbol) DO UPDATE SET
                    status       = EXCLUDED.status,
                    tick_size    = EXCLUDED.tick_size,
                    step_size    = EXCLUDED.step_size,
                    min_notional = EXCLUDED.min_notional,
                    updated_at   = now()
                """)
                .param("objectId", object.id())
                .param("exchange", spec.exchange())
                .param("symbol", spec.symbol())
                .param("baseAsset", spec.baseAsset())
                .param("quoteAsset", spec.quoteAsset())
                .param("status", spec.status().name())
                .param("tickSize", spec.tickSize())
                .param("stepSize", spec.stepSize())
                .param("minNotional", spec.minNotional())
                .update();

        return find(spec.exchange(), spec.symbol()).orElseThrow();
    }

    @Override
    public Optional<InstrumentSpec> spec(InstrumentRef instrument) {
        return jdbc.sql("""
                SELECT exchange, symbol, base_asset, quote_asset, status,
                       tick_size, step_size, min_notional
                  FROM instrument WHERE id = :id
                """)
                .param("id", instrument.id())
                .query((rs, n) -> new InstrumentSpec(
                        rs.getString("exchange"),
                        rs.getString("symbol"),
                        rs.getString("base_asset"),
                        rs.getString("quote_asset"),
                        InstrumentSpec.InstrumentStatus.valueOf(rs.getString("status")),
                        rs.getBigDecimal("tick_size"),
                        rs.getBigDecimal("step_size"),
                        rs.getBigDecimal("min_notional")))
                .optional();
    }

    /**
     * Ontolojideki {@code Instrument} tipini yoksa kurar.
     *
     * <p>Şema çalışma zamanında tanımlanabildiği için, bu modül kendi ihtiyaç duyduğu
     * tipi kendisi kurabiliyor — ayrı bir hazırlık adımı gerekmiyor.
     */
    private void ensureOntologyType() {
        if (ontology.objectType(INSTRUMENT_TYPE).isPresent()) {
            return;
        }
        CommitContext ctx = new CommitContext(ActorType.SYSTEM, "market-data",
                "enstrüman tipi kuruldu", null, null, null, null, null);
        ontology.defineObjectType(
                com.investor.ontology.model.NewObjectType.of(INSTRUMENT_TYPE, "Enstrüman"), ctx);
        define("symbol", "Sembol", true);
        define("exchange", "Borsa", false);
        define("baseAsset", "Taban Varlık", false);
        define("quoteAsset", "Karşı Varlık", false);
        define("status", "Durum", false);
    }

    private void define(String apiName, String displayName, boolean title) {
        CommitContext ctx = new CommitContext(ActorType.SYSTEM, "market-data",
                "enstrüman alanı", null, null, null, null, null);
        var spec = com.investor.ontology.model.NewPropertyType.of(
                apiName, displayName, com.investor.ontology.model.DataType.STRING);
        ontology.defineProperty(INSTRUMENT_TYPE, title ? spec.asTitle() : spec, ctx);
    }
}
