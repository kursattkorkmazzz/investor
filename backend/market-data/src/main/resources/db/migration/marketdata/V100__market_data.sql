-- ============================================================================
-- Piyasa verisi
--
-- Ontoloji "yavaş değişen gerçekler" içindir; yüksek frekanslı zaman serileri
-- buraya gelir. Ontolojideki Instrument nesnesi bu tablolara instrument.object_id
-- üzerinden bağlanır.
--
-- Flyway sürüm aralıkları (tek global sıra, modül başına blok):
--   1–99    ontology-core
--   100–199 market-data
--   200–299 knowledge
--   300–399 decision-engine
--
-- Bkz. docs/04-veri-katmani.md
-- ============================================================================

CREATE TABLE instrument (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    object_id     uuid           NOT NULL UNIQUE REFERENCES object_instance(id),
    exchange      text           NOT NULL,
    symbol        text           NOT NULL,
    base_asset    text           NOT NULL,
    quote_asset   text           NOT NULL,
    status        text           NOT NULL CHECK (status IN ('TRADING', 'HALT', 'DELISTED', 'UNKNOWN')),
    tick_size     numeric(24,12) NOT NULL CHECK (tick_size > 0),
    step_size     numeric(24,12) NOT NULL CHECK (step_size > 0),
    min_notional  numeric(24,8)  NOT NULL CHECK (min_notional >= 0),
    created_at    timestamptz    NOT NULL DEFAULT now(),
    updated_at    timestamptz    NOT NULL DEFAULT now(),
    UNIQUE (exchange, symbol)
);

COMMENT ON COLUMN instrument.object_id IS
    'Ontolojideki Instrument nesnesi. Piyasa verisi ile bilgi katmanı arasındaki tek bağ.';
COMMENT ON COLUMN instrument.tick_size IS
    'Fiyat adımı. Emir gönderirken fiyat buna hizalanmazsa borsa reddeder.';
COMMENT ON COLUMN instrument.step_size IS
    'Miktar adımı. Miktar her zaman AŞAĞI yuvarlanır — yukarı yuvarlama bakiyeyi aşabilir.';

-- ---------------------------------------------------------------------------
-- OHLCV
--
-- is_final, sessiz look-ahead hatalarının kaynağı: kapanmamış bir mumdan
-- hesaplanan indikatör mum kapanınca değişir. Karar üretimi yalnızca
-- is_final = true satırları okur (bkz. MarketDataReader).
-- ---------------------------------------------------------------------------
CREATE TABLE ohlcv (
    instrument_id   bigint        NOT NULL REFERENCES instrument(id),
    timeframe       text          NOT NULL CHECK (timeframe IN ('1m','5m','15m','1h','4h','1d')),
    open_time       timestamptz   NOT NULL,
    close_time      timestamptz   NOT NULL,
    open            numeric(24,12) NOT NULL,
    high            numeric(24,12) NOT NULL,
    low             numeric(24,12) NOT NULL,
    close           numeric(24,12) NOT NULL,
    volume          numeric(30,12) NOT NULL,
    quote_volume    numeric(30,12) NOT NULL,
    trade_count     integer        NOT NULL,
    taker_buy_base  numeric(30,12),
    is_final        boolean        NOT NULL DEFAULT false,
    ingested_at     timestamptz    NOT NULL DEFAULT now(),
    PRIMARY KEY (instrument_id, timeframe, open_time),
    CONSTRAINT ohlcv_time_order   CHECK (close_time > open_time),
    CONSTRAINT ohlcv_high_is_high CHECK (high >= low AND high >= open AND high >= close),
    CONSTRAINT ohlcv_low_is_low   CHECK (low  <= open AND low  <= close),
    CONSTRAINT ohlcv_non_negative CHECK (volume >= 0 AND quote_volume >= 0 AND trade_count >= 0)
) PARTITION BY RANGE (open_time);

CREATE INDEX ohlcv_final_idx ON ohlcv (instrument_id, timeframe, open_time DESC)
    WHERE is_final;

-- Türev metrikler: funding rate, open interest, long/short oranı.
CREATE TABLE derivative_metric (
    instrument_id bigint         NOT NULL REFERENCES instrument(id),
    metric        text           NOT NULL CHECK (metric IN
                      ('FUNDING_RATE', 'OPEN_INTEREST', 'LONG_SHORT_RATIO')),
    observed_at   timestamptz    NOT NULL,
    value         numeric(30,12) NOT NULL,
    ingested_at   timestamptz    NOT NULL DEFAULT now(),
    PRIMARY KEY (instrument_id, metric, observed_at)
) PARTITION BY RANGE (observed_at);

-- ---------------------------------------------------------------------------
-- Ingest ilerleme işareti
--
-- Backfill'in kaldığı yerden devam edebilmesi için. Ayrıca "bu enstrümanın
-- verisi ne kadar taze" sorusunun cevabı: OntologySnapshot bayat kaynakları
-- işaretlerken buraya bakar.
-- ---------------------------------------------------------------------------
CREATE TABLE ingest_watermark (
    instrument_id     bigint      NOT NULL REFERENCES instrument(id),
    timeframe         text        NOT NULL,
    last_final_open   timestamptz,
    last_attempt_at   timestamptz,
    last_success_at   timestamptz,
    consecutive_errors int        NOT NULL DEFAULT 0,
    last_error        text,
    PRIMARY KEY (instrument_id, timeframe)
);

-- ---------------------------------------------------------------------------
-- Partition yönetimi
--
-- pg_partman RDS'te bulunmayabilir ve build'i doğrulanmamış bir eklentiye
-- bağlamak istemiyoruz; aylık partition'ları kendimiz açıyoruz.
--
-- DEFAULT partition bilinçli olarak yok: aralık dışı bir yazma sessizce
-- yanlış yere düşmek yerine hata versin. Backfill kendi aralığının
-- partition'larını yazmadan önce açar.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ensure_month_partition(p_parent text, p_month date)
RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v_start date := date_trunc('month', p_month)::date;
    v_end   date := (date_trunc('month', p_month) + interval '1 month')::date;
    v_name  text := format('%s_%s', p_parent, to_char(v_start, 'YYYY_MM'));
BEGIN
    IF p_parent NOT IN ('ohlcv', 'derivative_metric') THEN
        RAISE EXCEPTION 'Bilinmeyen partition ebeveyni: %', p_parent;
    END IF;

    -- Şema adı sabitlenmiyor: search_path neyi gösteriyorsa orada oluşur.
    IF to_regclass(quote_ident(v_name)) IS NULL THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
            v_name, p_parent, v_start, v_end);
    END IF;

    RETURN v_name;
END;
$$;

COMMENT ON FUNCTION ensure_month_partition IS
    'Aylık partition''ı yoksa açar, adını döner. Idempotent.';

-- Uygulama açılmadan da ilk yazmanın çalışabilmesi için içinde bulunulan ay
-- ve komşuları hazır olsun.
SELECT ensure_month_partition('ohlcv', (now() - interval '1 month')::date);
SELECT ensure_month_partition('ohlcv', now()::date);
SELECT ensure_month_partition('ohlcv', (now() + interval '1 month')::date);
SELECT ensure_month_partition('derivative_metric', now()::date);
SELECT ensure_month_partition('derivative_metric', (now() + interval '1 month')::date);
