-- ============================================================================
-- Bilgi hatları: haber ve makro veri toplama
--
-- Buradaki tablolar *işletme* verisidir: neyi çektik, hangi kopya hangi kümeye
-- düştü, nereye kadar ilerledik. Anlamsal sonuç — NewsArticle, MacroIndicator,
-- MacroObservation — ontolojide yaşar.
--
-- Ayrımın sebebi: ontoloji "ne biliyoruz"u tutar, bu tablolar "nasıl öğrendik"i.
-- İkisini karıştırmak, ingest ayrıntılarının bilgi katmanına sızması demek.
--
-- Flyway sürüm aralığı: knowledge 200–299 (bkz. V100__market_data.sql)
-- ============================================================================

CREATE TABLE news_feed (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind               text        NOT NULL CHECK (kind IN ('RSS', 'ATOM', 'JSON_API')),
    name               text        NOT NULL,
    url                text        NOT NULL UNIQUE,
    language           text,
    reliability        numeric(4,3) NOT NULL DEFAULT 0.500
                           CHECK (reliability BETWEEN 0 AND 1),
    enabled            boolean     NOT NULL DEFAULT true,
    last_attempt_at    timestamptz,
    last_success_at    timestamptz,
    consecutive_errors int         NOT NULL DEFAULT 0,
    last_error         text,
    created_at         timestamptz NOT NULL DEFAULT now()
);

COMMENT ON COLUMN news_feed.reliability IS
    'Kaynağın geçmiş güvenilirliği. Kanıt ağırlığında kullanılır — her kaynak eşit değildir.';

-- ---------------------------------------------------------------------------
-- Yakın-kopya kümesi.
--
-- Aynı haber beş kaynaktan gelirse, dedup yapılmadığında sistem bunu "beş ayrı
-- kanıt" sayar ve haberin önemini yayın hacmiyle karıştırır. Haber odaklı
-- stratejilerdeki en yaygın sistematik hata budur.
--
-- Küme başına ontolojide TEK NewsArticle nesnesi olur; kaynak sayısı ayrı bir
-- alan olarak taşınır (ağırlığı etkiler, kanıt sayısını çoğaltmaz).
-- ---------------------------------------------------------------------------
CREATE TABLE news_cluster (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- İki aşamalı benzerlik: simhash ucuz ön eleme, normalized_text üzerinden
    -- hesaplanan Jaccard kesin karar. Tek başına simhash kısa başlıklarda gürültülü,
    -- tek başına Jaccard her kümeyle karşılaştırma gerektirir.
    simhash            bigint      NOT NULL,
    normalized_text    text        NOT NULL,
    source_count       int         NOT NULL DEFAULT 1 CHECK (source_count > 0),
    first_published_at timestamptz NOT NULL,
    last_published_at  timestamptz NOT NULL,
    object_id          uuid        REFERENCES object_instance(id),
    created_at         timestamptz NOT NULL DEFAULT now()
);

-- Kümeleme adayları son N saatteki kümeler arasından seçilir; bu indeks o taramayı taşır.
CREATE INDEX news_cluster_recent_idx ON news_cluster (last_published_at DESC);
CREATE INDEX news_cluster_object_idx ON news_cluster (object_id);

CREATE TABLE news_item (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    feed_id       uuid        NOT NULL REFERENCES news_feed(id),
    cluster_id    uuid        REFERENCES news_cluster(id),
    canonical_url text        NOT NULL,
    title         text        NOT NULL,
    summary       text,
    body          text,
    -- Haberin yayınlandığı an. Ontolojiye valid_from olarak gider.
    published_at  timestamptz NOT NULL,
    -- Bizim gördüğümüz an. Ontolojiye recorded_at olarak gider; backtest buna bakar.
    fetched_at    timestamptz NOT NULL DEFAULT now(),
    content_hash  text        NOT NULL,
    simhash       bigint      NOT NULL,
    UNIQUE (feed_id, canonical_url)
);

CREATE INDEX news_item_cluster_idx   ON news_item (cluster_id);
CREATE INDEX news_item_published_idx ON news_item (published_at DESC);
-- İçerik özeti üzerinde UNIQUE kısıt yok: bir hafta sonra aynı başlıkla yayınlanan
-- haber farklı bir olaydır. Birebir kopya kontrolü, kümeleme penceresiyle sınırlı
-- bir sorguyla yapılır.
CREATE INDEX news_item_content_idx ON news_item (content_hash, published_at DESC);

COMMENT ON COLUMN news_item.published_at IS
    'Yayın zamanı — ontolojide valid_from olur.';
COMMENT ON COLUMN news_item.fetched_at IS
    'Bizim gördüğümüz an — ontolojide recorded_at olur. Backtest bu eksene bakar: '
    'haberi o an gerçekten görmüş müydük?';

-- ---------------------------------------------------------------------------
-- Genel amaçlı ingest imleci: FRED serileri, ekonomik takvim, on-chain kaynaklar.
-- Piyasa verisindeki ingest_watermark'ın enstrümana bağlı olmayan karşılığı.
-- ---------------------------------------------------------------------------
CREATE TABLE ingest_cursor (
    source_key         text PRIMARY KEY,
    cursor_value       text,
    last_attempt_at    timestamptz,
    last_success_at    timestamptz,
    consecutive_errors int  NOT NULL DEFAULT 0,
    last_error         text
);
