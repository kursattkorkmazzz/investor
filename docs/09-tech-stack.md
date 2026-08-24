# 09 — Tech Stack

---

## Backend

| Bileşen | Seçim | Gerekçe |
|---|---|---|
| Dil | **Java 21 (LTS)** | Sanal iş parçacıkları (ajanları paralel koşturmak için), record'lar, sealed interface, pattern matching |
| Çatı | **Spring Boot 4.1** | Verilen kısıt; güncel kararlı sürüm. Jackson 3 ve bölünmüş autoconfiguration buradan geliyor ([ADR-0007](adr/0007-spring-boot-4.md)) |
| Modülerlik | **Spring Modulith 2.1** | Modül sınırlarını testte zorlar; event publication registry hazır outbox sağlar |
| Derleme | **Gradle (Kotlin DSL)** | Çok modüllü yapıda Maven'dan hızlı, yapılandırması okunur |
| Veri erişimi | **Spring `JdbcClient`** | Elle SQL. jOOQ kod üretimi build'i canlı veritabanına ya da Postgres'e özgü DDL ayrıştırmasına bağımlı kılıyordu ([ADR-0006](adr/0006-jooq-yerine-jdbcclient.md)). Tip güvenliğini gerçek PostgreSQL'e karşı koşan testler telafi ediyor. |
| Migration | **Flyway** | Modül başına migration klasörü |
| Teknik analiz | **ta4j** | Olgun Java indikatör kütüphanesi; hesap deterministik kalır |
| LLM | **Anthropic Java SDK** (`com.anthropic:anthropic-java`) | Yapılandırılmış çıktı, prompt caching, adaptive thinking, `effort` ve token muhasebesine gecikmesiz erişim |
| İstatistik / ML | **Tribuo** | Kalibrasyon için lojistik regresyon; hafif, Java-yerel |
| Dayanıklılık | **Resilience4j** | Circuit breaker, retry, rate limiter |
| Zamanlama kilidi | **ShedLock** | Çoklu instance'ta işin tek yerde koşması |
| Gözlemlenebilirlik | **Micrometer + OpenTelemetry** | LLM çağrıları span olarak izlenir; maliyet metrik olur |
| JSON | **Jackson 3** (`tools.jackson`) | Boot 4 varsayılanı. Ontoloji modülü kendi eşleyicisini kurar — saklanan JSON'un serileşmesi uygulamanın sunum ayarlarına bağlanmamalı. |

### Neden Spring AI değil de doğrudan SDK

Spring AI iyi bir soyutlama, ama sağlayıcı SDK'sının gerisinden geliyor — prompt
caching kırılma noktalarının konumlandırılması, `output_config` ile yapılandırılmış
çıktı, adaptive thinking, `effort` seviyesi ve token/maliyet muhasebesi bu sistemde
birinci sınıf ihtiyaçlar. Bunları kendi `LlmClient` port'umuzun arkasında doğrudan
SDK ile kullanıyoruz (bkz. [05](05-analiz-ajanlari.md)). Sağlayıcı bağımsızlığı ileride
gerçekten gerekirse, değişecek tek sınıf `LlmClient` implementasyonu.

### Kod üretimi neden yok

jOOQ'nun tip güvenliği cazipti ama kod üretimi ya build sırasında ayakta bir veritabanı
ister ya da DDL'i ayrıştırabilmeyi. Şemamız `EXCLUDE USING gist`, `tstzrange` ifadeleri,
kısmi indeksler ve plpgsql fonksiyonları içeriyor; ayrıştırıcı bunları kaçırırsa üretilen
kod şemanın bir kısmını sessizce ıskalar. Bunun yerine SQL elle yazılıyor ve gerçek
PostgreSQL'e karşı test ediliyor. Ayrıntı: [ADR-0006](adr/0006-jooq-yerine-jdbcclient.md).

### Neden JPA/Hibernate değil

Ontoloji EAV tabanlı ve sorgularının çoğu bitemporal. Hibernate'in nesne-ilişki eşlemesi
burada yardımcı olmuyor, engelliyor: `EXCLUDE` kısıtları, `tstzrange` operatörleri,
recursive CTE'ler ve kısmi indeks kullanan sorgular JPQL'de ifade edilemiyor.
jOOQ, yazacağımız SQL'i tip güvenli hale getiriyor — soyutlamaya çalışmıyor.

---

## Veri

| Bileşen | Seçim | Not |
|---|---|---|
| Veritabanı | **PostgreSQL 16 · AWS RDS** | Yönetilen; TimescaleDB yok (ADR-0003) |
| Eklentiler | `btree_gist`, `pg_trgm`, `vector`, `pg_partman`, `pg_cron` | Sürüm uyumu deploy öncesi doğrulanacak |
| Vektör arama | **pgvector (HNSW)** | Ayrı vektör DB'ye gerek yok; ölçek küçük |
| Cache / buffer | **Redis (Valkey)** | Canlı fiyat/derinlik, rate limit sayaçları, idempotency, snapshot cache |
| Nesne deposu | **S3** | Ham API cevapları, arşivlenen OHLCV (Parquet) |
| Gizli bilgiler | **AWS Secrets Manager** | Binance ve LLM anahtarları |

---

## Frontend

| Bileşen | Seçim |
|---|---|
| Çatı | React 19 + TypeScript |
| Derleme | Vite |
| Yönlendirme | TanStack Router |
| Sunucu durumu | TanStack Query |
| İstemci durumu | Zustand |
| Form + doğrulama | React Hook Form + Zod |
| Stil | Tailwind + shadcn/ui |
| Fiyat grafiği | lightweight-charts |
| Analitik grafik | Recharts |
| Graf | React Flow |
| Tablo | TanStack Table |
| Sayı hassasiyeti | decimal.js |
| API istemcisi | OpenAPI'den üretilen tipli istemci |

---

## Test

| Katman | Araç |
|---|---|
| Birim | JUnit 5 + AssertJ |
| Veritabanı | Testcontainers (gerçek PostgreSQL, eklentilerle) |
| Dış API | WireMock (Binance, FRED, haber kaynakları) |
| LLM | Kaydedilmiş cevap fikstürleri + şema doğrulama |
| Modül sınırları | Spring Modulith `ApplicationModuleTest` |
| Asenkron | Awaitility |
| Frontend | Vitest + Testing Library |
| Uçtan uca | Playwright |
| Yük | k6 (ingest hattı için) |

Test kuralları:
- Ontoloji ve decision engine testleri **gerçek PostgreSQL** üzerinde koşar. H2 kullanılmaz —
  `EXCLUDE` kısıtları, `tstzrange` ve kısmi indeksler H2'de yok; en kritik davranış
  test edilmemiş olurdu.
- `Clock` her yerde enjekte edilir; hiçbir yerde `Instant.now()` doğrudan çağrılmaz.
  Bitemporal davranışı test etmenin tek yolu zamanı kontrol edebilmek.
- Risk motoru için özellik tabanlı (property-based) testler: rastgele üretilen
  `intent`'ler hiçbir zaman limitleri aşan bir emre dönüşemez.

---

## Altyapı ve dağıtım

| Bileşen | Seçim | Aylık tahmini maliyet |
|---|---|---|
| Uygulama sunucusu | EC2 `t4g.small` (ARM) + Docker Compose | ~$12 |
| Veritabanı | RDS `db.t4g.micro` PostgreSQL 16 | ~$15 |
| Redis | Aynı sunucuda konteyner | $0 |
| S3 + Secrets + trafik | | ~$5 |
| **Toplam altyapı** | | **~$32** |

Tek kullanıcılı bir sistem için ECS/Fargate veya Kubernetes gereksiz maliyet ve
karmaşıklık. Tek EC2 + Docker Compose yeterli; RDS yönetilen kalıyor çünkü yedekleme
ve failover kendi başımıza yönetmek istemediğimiz tek şey.

Ölçek gerçekten gerekirse geçiş yolu açık: uygulama zaten durumsuz (durum Postgres ve
Redis'te), ShedLock zamanlanmış işleri koruyor.

Altyapı **Terraform** ile tanımlanır: VPC, RDS, güvenlik grupları, Secrets Manager,
S3, IAM rolleri. Uygulama dağıtımı GitHub Actions üzerinden.

### CI hattı

```
push → derle → birim testler → Testcontainers entegrasyon testleri
     → Modulith sınır doğrulaması → frontend build + test
     → Docker imajı → staging'e dağıt → shadow modda koş
     → kapı metrikleri geçerse → canlıya dağıt
```

Son iki adım [06](06-risk-ve-execution.md)'daki shadow kapısını uygular:
`analysis`, `decision-engine` veya `risk` modülüne dokunan hiçbir değişiklik shadow
doğrulaması olmadan canlıya çıkamaz.

---

## Bilinçli olarak seçilmeyenler

| Teknoloji | Neden şimdi değil | Ne zaman yeniden değerlendirilir |
|---|---|---|
| **Kafka / Redpanda** | Tek instance, tek kullanıcı. Transactional outbox aynı garantileri operasyon yükü olmadan veriyor. | Modülleri ayrı servislere bölmek gerekirse; outbox tablosu darboğaz olursa |
| **TimescaleDB** | RDS desteklemiyor. Tick saklamadığımız için hacim zaten küçük. | Kendi Postgres'imizi barındırmaya geçersek |
| **Neo4j / Apache AGE** | 3–4 hop'a kadar recursive CTE yeterli | Graf sorguları 4+ hop'a çıkarsa veya yol bulma/merkezilik analizi gerekirse |
| **Python quant sidecar** | Tek kod yolu (backtest = canlı) daha değerli görüldü | Derin öğrenme veya ağır sayısal optimizasyon gerçekten gerekirse — o zaman offline araç olarak, prod yolunda değil |
| **Kubernetes** | Tek konteyner grubu için maliyet ve karmaşıklık | Çoklu bölge veya çoklu kullanıcı |
| **Ayrı vektör DB** | pgvector bu ölçekte fazlasıyla yeterli | Milyonlarca embedding'e çıkarsa |
| **Hibernate/JPA** | EAV + bitemporal ile uyumsuz | — |
| **jOOQ** | Kod üretimi build'e canlı DB ya da DDL ayrıştırma bağımlılığı ekliyordu | Elle SQL'de kolon/tip hataları tekrarlayan bir sorun hâline gelirse |
