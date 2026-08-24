# ADR-0003 — Veritabanı: AWS RDS PostgreSQL, TimescaleDB yok

**Durum:** Kabul edildi · 2026-08-24

## Bağlam

Zaman serisi verisi (OHLCV) saklanacak ve üst zaman dilimlerine toplanacak.
TimescaleDB bu iş için doğal aday — özellikle `continuous aggregate` özelliği,
1m mumlardan 5m/15m/1h/1d rollup'larını otomatik türetip güncel tutuyor.

Ancak AWS RDS ve Aurora TimescaleDB eklentisini desteklemiyor. Yönetilen veritabanı
tercih edildiğinde bu özellik masadan kalkıyor.

Hacim tarafında belirleyici olan, ham tick verisini saklamama kararı: karar döngüsü
15 dakikada bir çalışan bir LLM sisteminde tick geçmişinin kullanımı yok. Bu kararla
yıllık hacim ~200 GB'dan ~5 GB'a düşüyor.

## Karar

AWS RDS PostgreSQL 16, düz. TimescaleDB yok.

- Partition yönetimi: native declarative partitioning + `pg_partman` (aylık)
- Rollup: kendi `@Scheduled` + ShedLock job'larımız, idempotent `INSERT ... ON CONFLICT`
- Eklentiler: `btree_gist`, `pg_trgm`, `vector` (pgvector), `pg_partman`, `pg_cron`

## Sonuçlar

**Olumlu**
- Yedekleme, failover, patch yönetimi AWS'te
- Ontoloji, kararlar, piyasa verisi ve vektörler tek veritabanında — tek transaction sınırı
- pgvector RDS'te mevcut; ayrı vektör DB'ye gerek yok
- Timescale'in lisans ve sürüm bağımlılığı yok

**Olumsuz**
- Rollup mantığını kendimiz yazıyoruz (~300 satır) ve doğruluğundan sorumluyuz
- Eksik 1m mumdan üretilmiş bozuk rollup riski var — `HAVING count(*) = expectedBars`
  koruması ve boşluk doldurma job'ı ile kapatılıyor
- Timescale'in sıkıştırması yok; hacim büyürse arşivleme (S3/Parquet) elle yönetilecek
- `pg_partman` / `pg_cron` kullanılabilirliği hedef RDS sürümünde doğrulanmalı;
  yoksa partition oluşturma da Spring job'una taşınır

## Değerlendirilen alternatifler

**Timescale Cloud.** Hypertable ve continuous aggregate + yönetilen operasyon.
Üçüncü bir sağlayıcıya bağımlılık ve ek maliyet; ontoloji ile piyasa verisini
ayrı veritabanlarına bölmek istemedik.

**Self-host Postgres + TimescaleDB.** En çok özellik. Yedekleme ve failover
sorumluluğunu üstlenmek, tek kişilik bir projede kazanılan özellikten pahalı görüldü.

**Amazon Timestream.** Kripto OHLCV için uygun, ama ontolojiyle join edilemez;
ikinci bir veri kaynağı yükü getirir.

## Yeniden değerlendirme koşulları

- Tick verisi saklamak gerçekten gerekirse (strateji mikroyapıya inerse)
- OHLCV hacmi RDS instance'ını zorlarsa
- Rollup job'ları tekrarlayan doğruluk sorunları üretirse — o noktada Timescale'in
  continuous aggregate'i, barındırma değişikliğinin bedelini haklı çıkarır
