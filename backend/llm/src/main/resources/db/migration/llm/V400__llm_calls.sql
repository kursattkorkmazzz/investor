-- LLM çağrı kaydı.
--
-- Neden var: karar motorunun "neden böyle karar verildi" sorusuna cevap verebilmesi için
-- modelin ne sorulduğunda ne dediğinin kaydı gerekiyor. Bir kararın gerekçesi "LLM öyle
-- dedi" ise, o "öyle dedi"nin kendisi kanıt olarak durmalı; sonradan üretilemez.
--
-- İkinci işlevi maliyet: aylık bütçe tavanı yeniden başlatmadan sonra bu tablodan geri
-- yükleniyor. Tavan süreç belleğinde tutulsaydı her yeniden başlatma onu sıfırlar ve
-- tavan hiçbir şey ifade etmezdi.

CREATE TABLE llm_call (
    id                   uuid PRIMARY KEY,
    occurred_at          timestamptz NOT NULL,
    purpose              text        NOT NULL,
    model_id             text        NOT NULL,
    -- İstemin tamamı değil özeti: güvenilmeyen metin zaten kaynak tablosunda duruyor,
    -- ikinci bir kopya hem yer harcar hem de düşman içeriği bir yerde daha çoğaltır.
    -- Hash, "aynı istemi mi gönderdik" sorusuna cevap vermeye yetiyor.
    prompt_hash          text        NOT NULL,
    prompt_chars         integer     NOT NULL,
    input_tokens         integer     NOT NULL DEFAULT 0,
    cached_input_tokens  integer     NOT NULL DEFAULT 0,
    output_tokens        integer     NOT NULL DEFAULT 0,
    reasoning_tokens     integer     NOT NULL DEFAULT 0,
    cost_usd             numeric(14, 8) NOT NULL DEFAULT 0,
    latency_ms           integer     NOT NULL,
    -- Modelin ham cevabı. Doğrulama başarısız olsa bile yazılır: başarısızlığın nedenini
    -- ancak modelin ne dediğine bakarak anlayabiliriz.
    response_raw         text,
    -- Şema sınırları dışına çıkıp kırpılan alanlar. Bu listenin dolmaya başlaması ya
    -- modelin ya da istemin bozulduğunun erken işareti.
    clamped_fields       text[]      NOT NULL DEFAULT '{}',
    error                text,
    metadata             jsonb       NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT llm_call_tokens_nonneg CHECK (
        input_tokens >= 0 AND output_tokens >= 0
        AND cached_input_tokens >= 0 AND reasoning_tokens >= 0
        AND cached_input_tokens <= input_tokens
    )
);

COMMENT ON TABLE llm_call IS
    'Her LLM çağrısının denetim ve maliyet kaydı. Sadece ekleme yapılır; güncelleme yok.';

-- Aylık toplam sorgusu (bütçe geri yükleme) ve zaman aralığı taramaları için.
-- BRIN yeterli: tablo zamana göre doğal sıralı yazılıyor ve satırlar küçük.
CREATE INDEX llm_call_occurred_at_brin ON llm_call USING brin (occurred_at);
CREATE INDEX llm_call_purpose_occurred_at ON llm_call (purpose, occurred_at DESC);
-- Kaynak kaydından çağrıya gitmek için (ör. bir haber için hangi çıkarım yapıldı).
CREATE INDEX llm_call_metadata_gin ON llm_call USING gin (metadata jsonb_path_ops);

-- Kayıt değiştirilemez: bir kararın gerekçesi sonradan düzenlenebiliyorsa gerekçe değildir.
-- Rol yetkilerine (REVOKE) güvenmiyoruz; trigger ile zorluyoruz — ontology_change_log ile
-- aynı yaklaşım. Testler sıfırlarken trigger'ı geçici olarak kapatır.
CREATE OR REPLACE FUNCTION llm_call_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'llm_call salt-ekleme bir tablodur (% denendi)', TG_OP;
END;
$$;

CREATE TRIGGER llm_call_no_mutate
    BEFORE UPDATE OR DELETE ON llm_call
    FOR EACH ROW EXECUTE FUNCTION llm_call_append_only();

CREATE TRIGGER llm_call_no_truncate
    BEFORE TRUNCATE ON llm_call
    FOR EACH STATEMENT EXECUTE FUNCTION llm_call_append_only();
