-- ============================================================================
-- Ontoloji çekirdeği — bitemporal EAV
--
-- İki zaman ekseni:
--   valid_from / valid_to      : bilgi gerçek dünyada ne zaman doğruydu
--   recorded_at / retracted_at : biz ne zaman öğrendik / ne zaman yanlış olduğunu anladık
--
-- Hiçbir değer UPDATE ile ezilmez, hiçbir satır DELETE edilmez. Güncelleme
-- "kapat ve ekle", silme "damgala"dır.
--
-- Bkz. docs/02-ontoloji.md
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist;   -- EXCLUDE içinde uuid eşitliği
CREATE EXTENSION IF NOT EXISTS pg_trgm;      -- başlık araması


-- ============================================================================
-- META KATMANI — şema da veridir
-- ============================================================================

CREATE TABLE object_type (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    api_name        text        NOT NULL UNIQUE,
    display_name    text        NOT NULL,
    description     text,
    icon            text,
    is_abstract     boolean     NOT NULL DEFAULT false,
    parent_type_id  uuid        REFERENCES object_type(id),
    current_version int         NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      text        NOT NULL,
    CONSTRAINT object_type_api_name_fmt CHECK (api_name ~ '^[A-Z][A-Za-z0-9]*$'),
    CONSTRAINT object_type_no_self_parent CHECK (parent_type_id IS DISTINCT FROM id)
);

COMMENT ON TABLE object_type IS 'Ontolojideki nesne tipleri. parent_type_id ile kalıtım (Asset -> CryptoAsset).';

CREATE TABLE object_type_version (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    object_type_id uuid        NOT NULL REFERENCES object_type(id),
    version        int         NOT NULL,
    status         text        NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'DEPRECATED')),
    spec           jsonb       NOT NULL,
    change_note    text,
    valid_from     timestamptz NOT NULL DEFAULT now(),
    valid_to       timestamptz NOT NULL DEFAULT 'infinity',
    created_by     text        NOT NULL,
    UNIQUE (object_type_id, version)
);

COMMENT ON COLUMN object_type_version.spec IS 'O sürümdeki tam şema anlık görüntüsü — denetim ve replay için.';

CREATE TABLE property_type (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    object_type_id        uuid    NOT NULL REFERENCES object_type(id),
    api_name              text    NOT NULL,
    display_name          text    NOT NULL,
    description           text,
    data_type             text    NOT NULL CHECK (data_type IN (
                              'STRING', 'TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN',
                              'TIMESTAMP', 'DATE', 'ENUM', 'JSON', 'REFERENCE')),
    cardinality           text    NOT NULL DEFAULT 'SINGLE'
                              CHECK (cardinality IN ('SINGLE', 'LIST')),
    is_required           boolean NOT NULL DEFAULT false,
    is_title              boolean NOT NULL DEFAULT false,
    unit                  text,
    constraints           jsonb   NOT NULL DEFAULT '{}'::jsonb,
    default_value         jsonb,
    introduced_in_version int     NOT NULL DEFAULT 1,
    deprecated_in_version int,
    display_order         int     NOT NULL DEFAULT 0,
    UNIQUE (object_type_id, api_name),
    CONSTRAINT property_type_api_name_fmt CHECK (api_name ~ '^[a-z][A-Za-z0-9]*$')
);

COMMENT ON COLUMN property_type.constraints IS 'min/max/minLength/maxLength/pattern/enumValues — uygulama tarafında doğrulanır.';

-- Tip başına en fazla bir başlık alanı
CREATE UNIQUE INDEX property_type_single_title_idx
    ON property_type (object_type_id) WHERE is_title;

CREATE TABLE link_type (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    api_name              text    NOT NULL UNIQUE,
    display_name          text    NOT NULL,
    reverse_api_name      text    NOT NULL,
    reverse_display_name  text    NOT NULL,
    from_type_id          uuid    NOT NULL REFERENCES object_type(id),
    to_type_id            uuid    NOT NULL REFERENCES object_type(id),
    cardinality           text    NOT NULL CHECK (cardinality IN
                              ('ONE_TO_ONE', 'ONE_TO_MANY', 'MANY_TO_ONE', 'MANY_TO_MANY')),
    is_symmetric          boolean NOT NULL DEFAULT false,
    property_schema       jsonb   NOT NULL DEFAULT '{}'::jsonb,
    introduced_in_version int     NOT NULL DEFAULT 1,
    CONSTRAINT link_type_api_name_fmt CHECK (api_name ~ '^[A-Z][A-Z0-9_]*$')
);


-- ============================================================================
-- KÖKEN KATMANI
-- ============================================================================

CREATE TABLE data_source (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind        text         NOT NULL CHECK (kind IN (
                    'BINANCE_REST', 'BINANCE_WS', 'RSS', 'NEWS_API', 'FRED',
                    'COINGECKO', 'ONCHAIN', 'LLM_INFERENCE', 'MANUAL', 'DERIVED')),
    name        text         NOT NULL,
    uri         text,
    fetched_at  timestamptz,
    raw_ref     text,
    reliability numeric(4,3) NOT NULL DEFAULT 0.500
                    CHECK (reliability BETWEEN 0 AND 1),
    metadata    jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON COLUMN data_source.raw_ref IS 'Ham cevabın S3 anahtarı — parse hatası bulunursa geçmiş yeniden işlenebilsin.';

CREATE TABLE ontology_commit (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_type  text        NOT NULL CHECK (actor_type IN
                    ('HUMAN', 'INGESTOR', 'LLM_AGENT', 'SYSTEM', 'MIGRATION')),
    actor_id    text        NOT NULL,
    reason      text,
    decision_id uuid,
    source_id   uuid        REFERENCES data_source(id),
    created_at  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE ontology_commit IS 'Atomik değişiklik birimi: "bu 12 alan aynı anda, aynı sebeple, aynı kaynaktan değişti".';

CREATE TABLE ontology_change_log (
    seq              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    commit_id        uuid        NOT NULL REFERENCES ontology_commit(id),
    op               text        NOT NULL CHECK (op IN (
                         'CREATE_OBJECT', 'SET_PROPERTY', 'CLOSE_PROPERTY', 'RETRACT_PROPERTY',
                         'ADD_LINK', 'CLOSE_LINK', 'RETRACT_LINK', 'DELETE_OBJECT',
                         'CREATE_TYPE', 'ALTER_TYPE')),
    object_id        uuid,
    property_type_id uuid,
    link_type_id     uuid,
    before           jsonb,
    after            jsonb,
    occurred_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ontology_change_log_commit_idx ON ontology_change_log (commit_id);
CREATE INDEX ontology_change_log_object_idx ON ontology_change_log (object_id, seq);

-- Denetim defteri append-only. Rol yetkilerine (REVOKE) güvenmiyoruz; trigger ile zorluyoruz.
CREATE OR REPLACE FUNCTION ontology_change_log_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ontology_change_log append-only bir defterdir; % engellendi', TG_OP;
END;
$$;

CREATE TRIGGER ontology_change_log_no_mutate
    BEFORE UPDATE OR DELETE ON ontology_change_log
    FOR EACH ROW EXECUTE FUNCTION ontology_change_log_append_only();

CREATE TRIGGER ontology_change_log_no_truncate
    BEFORE TRUNCATE ON ontology_change_log
    FOR EACH STATEMENT EXECUTE FUNCTION ontology_change_log_append_only();


-- ============================================================================
-- INSTANCE KATMANI
-- ============================================================================

CREATE TABLE object_instance (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    object_type_id uuid        NOT NULL REFERENCES object_type(id),
    external_id    text        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_commit uuid        NOT NULL REFERENCES ontology_commit(id),
    deleted_at     timestamptz,
    deleted_commit uuid        REFERENCES ontology_commit(id),
    UNIQUE (object_type_id, external_id)
);

COMMENT ON COLUMN object_instance.external_id IS 'Doğal anahtar: BINANCE:BTCUSDT, FRED:CPIAUCSL gibi.';

-- ---------------------------------------------------------------------------
-- Ontolojinin kalbi. Her alan değeri kendi satırında, bitemporal.
--
-- "Alanın değeri yok" durumu, tüm değer kolonlarının NULL olduğu bir satırla
-- değil, geçerlilik aralığının kapatılmasıyla ifade edilir.
-- ---------------------------------------------------------------------------
CREATE TABLE property_value (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    object_id        uuid        NOT NULL REFERENCES object_instance(id),
    property_type_id uuid        NOT NULL REFERENCES property_type(id),
    ordinal          smallint    NOT NULL DEFAULT 0,

    value_text       text,
    value_numeric    numeric,
    value_bool       boolean,
    value_ts         timestamptz,
    value_json       jsonb,
    value_ref        uuid        REFERENCES object_instance(id),

    valid_from       timestamptz NOT NULL,
    valid_to         timestamptz NOT NULL DEFAULT 'infinity',

    recorded_at      timestamptz NOT NULL DEFAULT now(),
    retracted_at     timestamptz,

    commit_id        uuid        NOT NULL REFERENCES ontology_commit(id),
    data_source_id   uuid        REFERENCES data_source(id),
    confidence       numeric(4,3) CHECK (confidence BETWEEN 0 AND 1),

    CONSTRAINT pv_valid_range CHECK (valid_from < valid_to),
    CONSTRAINT pv_retraction_after_record CHECK (retracted_at IS NULL OR retracted_at >= recorded_at),
    CONSTRAINT pv_exactly_one_value CHECK (
        (value_text    IS NOT NULL)::int + (value_numeric IS NOT NULL)::int +
        (value_bool    IS NOT NULL)::int + (value_ts      IS NOT NULL)::int +
        (value_json    IS NOT NULL)::int + (value_ref     IS NOT NULL)::int = 1
    )
);

-- Aynı obje + alan + ordinal için çakışan iki "gerçek" veritabanı seviyesinde imkânsız.
-- Uygulama hatalarının sessizce ikili gerçek üretmesini engelleyen kısıt budur.
ALTER TABLE property_value ADD CONSTRAINT pv_no_overlapping_truth
    EXCLUDE USING gist (
        object_id        WITH =,
        property_type_id WITH =,
        ordinal          WITH =,
        tstzrange(valid_from, valid_to) WITH &&
    ) WHERE (retracted_at IS NULL);

CREATE INDEX pv_current_idx  ON property_value (object_id, property_type_id)
    WHERE valid_to = 'infinity' AND retracted_at IS NULL;
CREATE INDEX pv_object_idx   ON property_value (object_id, property_type_id, valid_from DESC);
CREATE INDEX pv_recorded_idx ON property_value (recorded_at DESC);
CREATE INDEX pv_commit_idx   ON property_value (commit_id);
CREATE INDEX pv_numeric_idx  ON property_value (property_type_id, value_numeric)
    WHERE value_numeric IS NOT NULL AND retracted_at IS NULL;
CREATE INDEX pv_ref_idx      ON property_value (value_ref) WHERE value_ref IS NOT NULL;

CREATE TABLE link_instance (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    link_type_id    uuid        NOT NULL REFERENCES link_type(id),
    from_object_id  uuid        NOT NULL REFERENCES object_instance(id),
    to_object_id    uuid        NOT NULL REFERENCES object_instance(id),
    properties      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    weight          numeric,

    valid_from      timestamptz NOT NULL,
    valid_to        timestamptz NOT NULL DEFAULT 'infinity',
    recorded_at     timestamptz NOT NULL DEFAULT now(),
    retracted_at    timestamptz,

    commit_id       uuid        NOT NULL REFERENCES ontology_commit(id),
    data_source_id  uuid        REFERENCES data_source(id),
    confidence      numeric(4,3) CHECK (confidence BETWEEN 0 AND 1),

    CONSTRAINT li_valid_range CHECK (valid_from < valid_to),
    CONSTRAINT li_no_self_link CHECK (from_object_id <> to_object_id)
);

ALTER TABLE link_instance ADD CONSTRAINT li_no_overlapping_truth
    EXCLUDE USING gist (
        link_type_id   WITH =,
        from_object_id WITH =,
        to_object_id   WITH =,
        tstzrange(valid_from, valid_to) WITH &&
    ) WHERE (retracted_at IS NULL);

CREATE INDEX li_from_idx ON link_instance (from_object_id, link_type_id)
    WHERE valid_to = 'infinity' AND retracted_at IS NULL;
CREATE INDEX li_to_idx   ON link_instance (to_object_id, link_type_id)
    WHERE valid_to = 'infinity' AND retracted_at IS NULL;
CREATE INDEX li_asof_idx ON link_instance (from_object_id, valid_from DESC);


-- ============================================================================
-- OKUMA KATMANI — güncel durumun denormalize projeksiyonu
-- ============================================================================

CREATE TABLE object_current (
    object_id      uuid PRIMARY KEY REFERENCES object_instance(id),
    object_type_id uuid        NOT NULL REFERENCES object_type(id),
    type_api_name  text        NOT NULL,
    external_id    text        NOT NULL,
    title          text,
    data           jsonb       NOT NULL DEFAULT '{}'::jsonb,
    link_summary   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    last_commit_id uuid,
    updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX object_current_data_idx  ON object_current USING gin (data jsonb_path_ops);
CREATE INDEX object_current_type_idx  ON object_current (type_api_name);
CREATE INDEX object_current_title_idx ON object_current USING gin (title gin_trgm_ops);


-- ---------------------------------------------------------------------------
-- Projeksiyon tazeleme. Yazma transaction'ının içinde çağrılır, böylece
-- object_current ile property_value arasında okuma tutarsızlığı oluşmaz.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION refresh_object_current(p_object_id uuid) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    v_type_id     uuid;
    v_type_name   text;
    v_external_id text;
    v_deleted     timestamptz;
    v_title_field text;
    v_title       text;
    v_data        jsonb;
    v_links       jsonb;
    v_last_commit uuid;
BEGIN
    SELECT oi.object_type_id, ot.api_name, oi.external_id, oi.deleted_at
      INTO v_type_id, v_type_name, v_external_id, v_deleted
      FROM object_instance oi
      JOIN object_type ot ON ot.id = oi.object_type_id
     WHERE oi.id = p_object_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    -- Soft-delete edilmiş nesne projeksiyondan düşer; geçmişi property_value'da durur.
    IF v_deleted IS NOT NULL THEN
        DELETE FROM object_current WHERE object_id = p_object_id;
        RETURN;
    END IF;

    WITH open_values AS (
        SELECT pt.api_name,
               pt.cardinality,
               pv.ordinal,
               CASE pt.data_type
                   WHEN 'STRING'    THEN to_jsonb(pv.value_text)
                   WHEN 'TEXT'      THEN to_jsonb(pv.value_text)
                   WHEN 'ENUM'      THEN to_jsonb(pv.value_text)
                   WHEN 'INTEGER'   THEN to_jsonb(pv.value_numeric)
                   WHEN 'DECIMAL'   THEN to_jsonb(pv.value_numeric)
                   WHEN 'BOOLEAN'   THEN to_jsonb(pv.value_bool)
                   WHEN 'TIMESTAMP' THEN to_jsonb(pv.value_ts)
                   WHEN 'DATE'      THEN to_jsonb(pv.value_ts)
                   WHEN 'JSON'      THEN pv.value_json
                   WHEN 'REFERENCE' THEN to_jsonb(pv.value_ref)
               END AS jval
          FROM property_value pv
          JOIN property_type pt ON pt.id = pv.property_type_id
         WHERE pv.object_id = p_object_id
           AND pv.valid_to = 'infinity'
           AND pv.retracted_at IS NULL
    ),
    collapsed AS (
        SELECT api_name,
               CASE WHEN cardinality = 'LIST'
                    THEN jsonb_agg(jval ORDER BY ordinal)
                    ELSE (array_agg(jval ORDER BY ordinal))[1]
               END AS jval
          FROM open_values
         GROUP BY api_name, cardinality
    )
    SELECT COALESCE(jsonb_object_agg(api_name, jval), '{}'::jsonb)
      INTO v_data
      FROM collapsed;

    WITH open_links AS (
        SELECT lt.api_name,
               jsonb_build_object(
                   'objectId',   li.to_object_id,
                   'externalId', tgt.external_id,
                   'typeApiName', tot.api_name,
                   'weight',     li.weight,
                   'properties', li.properties
               ) AS entry
          FROM link_instance li
          JOIN link_type       lt  ON lt.id  = li.link_type_id
          JOIN object_instance tgt ON tgt.id = li.to_object_id
          JOIN object_type     tot ON tot.id = tgt.object_type_id
         WHERE li.from_object_id = p_object_id
           AND li.valid_to = 'infinity'
           AND li.retracted_at IS NULL
           AND tgt.deleted_at IS NULL
    ),
    grouped AS (
        SELECT api_name, jsonb_agg(entry) AS entries FROM open_links GROUP BY api_name
    )
    SELECT COALESCE(jsonb_object_agg(api_name, entries), '{}'::jsonb)
      INTO v_links
      FROM grouped;

    -- Başlık alanı kalıtılmış olabilir (Asset.name -> CryptoAsset). Hiyerarşiyi yukarı tara;
    -- en türemiş tipte tanımlı olan kazanır.
    WITH RECURSIVE ancestry AS (
        SELECT id, parent_type_id, 0 AS depth
          FROM object_type WHERE id = v_type_id
        UNION ALL
        SELECT ot.id, ot.parent_type_id, a.depth + 1
          FROM object_type ot JOIN ancestry a ON ot.id = a.parent_type_id
    )
    SELECT pt.api_name INTO v_title_field
      FROM property_type pt
      JOIN ancestry a ON a.id = pt.object_type_id
     WHERE pt.is_title
     ORDER BY a.depth
     LIMIT 1;

    v_title := COALESCE(
        CASE WHEN v_title_field IS NULL THEN NULL ELSE v_data ->> v_title_field END,
        v_external_id);

    SELECT x.commit_id INTO v_last_commit
      FROM (
          SELECT commit_id, recorded_at FROM property_value WHERE object_id = p_object_id
          UNION ALL
          SELECT commit_id, recorded_at FROM link_instance  WHERE from_object_id = p_object_id
      ) x
     ORDER BY x.recorded_at DESC
     LIMIT 1;

    INSERT INTO object_current (object_id, object_type_id, type_api_name, external_id,
                                title, data, link_summary, last_commit_id, updated_at)
    VALUES (p_object_id, v_type_id, v_type_name, v_external_id,
            v_title, v_data, v_links, v_last_commit, now())
    ON CONFLICT (object_id) DO UPDATE SET
        object_type_id = EXCLUDED.object_type_id,
        type_api_name  = EXCLUDED.type_api_name,
        external_id    = EXCLUDED.external_id,
        title          = EXCLUDED.title,
        data           = EXCLUDED.data,
        link_summary   = EXCLUDED.link_summary,
        last_commit_id = EXCLUDED.last_commit_id,
        updated_at     = EXCLUDED.updated_at;
END;
$$;
