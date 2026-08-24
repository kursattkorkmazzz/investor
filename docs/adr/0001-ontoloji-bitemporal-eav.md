# ADR-0001 — Ontoloji: Postgres üzerinde bitemporal EAV

**Durum:** Kabul edildi · 2026-08-24

## Bağlam

Sistemin tamamen dinamik bir bilgi katmanına ihtiyacı var: veri modeli, verinin
kendisi, alanlar, alan değerleri, ilişkiler ve versiyonlama. Versiyonlama gereksinimi
spesifik — bir nesnenin tek bir alanı veya tamamı güncellenebilmeli, eski değerler
kaybolmamalı.

Bunun üstüne, projeye özgü ve pazarlıksız bir gereksinim var: **backtest dürüstlüğü.**
Bir kararı denetlerken veya geçmişi yeniden oynatırken "o anda ne biliyorduk"
sorusunun cevaplanabilmesi gerekiyor. Bu, tek zaman eksenli bir tasarımla mümkün değil.

## Karar

PostgreSQL üzerinde meta/instance ayrımı olan, bitemporal bir EAV modeli:

- **Meta:** `object_type`, `object_type_version`, `property_type`, `link_type`
- **Instance:** `object_instance`, `property_value`, `link_instance`
- **Köken:** `ontology_commit`, `data_source`, `ontology_change_log` (append-only)
- **Okuma:** `object_current` (JSONB projeksiyon) + `object_embedding` (pgvector)

İki zaman ekseni: geçerlilik zamanı (`valid_from`/`valid_to`) ve kayıt zamanı
(`recorded_at`/`retracted_at`). Güncelleme, `UPDATE` değil kapat-ve-ekle; silme,
`DELETE` değil damgalama. Çakışan geçerlilik aralıkları `EXCLUDE USING gist` ile
veritabanı seviyesinde imkânsız.

Kritik tamamlayıcı kural: **yüksek frekanslı zaman serileri ontolojide durmaz.**
OHLCV ve türev metrikler kendi partition'lı tablolarında yaşar. Ontoloji "yavaş
değişen gerçekler" içindir.

## Sonuçlar

**Olumlu**
- Alan bazlı versiyonlama doğal olarak çalışıyor; ayrı bir geçmiş tablosu gerekmiyor
- "As-of" sorguları mümkün → dürüst backtest, denetlenebilir kararlar
- Her değerin kaynağı ve güven skoru satır seviyesinde taşınıyor
- Şema değişikliği migration gerektirmiyor; frontend şemayı çalışma zamanında öğreniyor
- Tek veritabanı, tek transaction sınırı

**Olumsuz**
- Sorgular normalize bir şemadan karmaşık; ORM yardımcı olmuyor (bkz. jOOQ tercihi)
- Okuma performansı için projeksiyon tablosu tutmak ve tutarlı güncellemek gerekiyor
- Tip güvenliği çalışma zamanına kayıyor; şema doğrulaması uygulama sorumluluğu
- "Yavaş değişen gerçekler" kuralı disiplin gerektiriyor; ihlal edilirse tablo şişer

## Değerlendirilen alternatifler

**Normalize şema + geçmiş tabloları.** Sorgular basit olurdu, ama her yeni varlık
sınıfı ve alan için migration gerekirdi — "tüm veriler dinamik olacak" gereksinimini
karşılamıyor.

**Doküman veritabanı (MongoDB).** Dinamiklik doğal, ama alan seviyesi bitemporal
versiyonlama ve ilişki bütünlüğü elle kurulmak zorunda; `EXCLUDE` benzeri bir garanti yok.

**Postgres + Neo4j.** En güçlü graf gezinme. İki veri kaynağı arasında CDC/senkron
yükü ve tutarlılık riski, elde edilen faydadan büyük görüldü — mevcut ihtiyaç 3–4 hop.

**Apache AGE.** Postgres içinde Cypher; tek DB avantajı korunurdu. Olgunluk ve
operasyon riski, recursive CTE'nin yeterli olduğu bir aşamada gereksiz bulundu.

## Yeniden değerlendirme koşulları

- Graf sorguları düzenli olarak 4+ hop'a çıkarsa veya yol bulma / merkezilik analizi gerekirse → AGE veya Neo4j
- `property_value` 100M satırı aşar ve projeksiyon tazeleme darboğaz olursa → bölümleme veya CQRS ayrımı
- Ontoloji alan tiplerinde çalışma zamanı hatalarının sıklığı kabul edilemez olursa → tip üretimi (codegen) katmanı
