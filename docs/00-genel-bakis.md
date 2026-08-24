# 00 — Genel Bakış ve Alınan Kararlar

## Sistem ne yapacak?

Kendisine tahsis edilen sermayeyi, kripto piyasalarında otonom al-sat yaparak büyütmeye
çalışan bir sistem. Kararları bir LLM veriyor; ama LLM'i "sihirli kutu" olarak değil,
**denetlenebilir, kalibre edilebilir ve öğrenebilir** bir bileşen olarak konumlandırıyoruz.

Sistemin dört bilgi kaynağı var:

1. **Teknik analiz** — fiyat/hacim serileri üzerinden hesaplanan indikatörler ve formasyonlar
2. **Temel analiz** — kripto için tokenomics, unlock takvimi, TVL, geliştirici aktivitesi,
   on-chain akışlar; ileride hisse için finansal tablolar
3. **Haber akışı** — RSS/API kaynaklarından toplanan, ontolojideki varlıklara bağlanan,
   önem ve yön açısından skorlanan haberler
4. **Makro/mikro ekonomi** — faiz, enflasyon, DXY, likidite koşulları; kripto-özel olarak
   BTC dominance, funding rate, stablecoin arzı, borsa rezervleri

Bu dördü ontolojide birleşir, analiz ajanları bunlardan **kanıt (evidence)** üretir,
decision engine kanıtları bir karara dönüştürür, risk motoru vetolar, execution katmanı
uygular, sonuç geri beslenir.

---

## Tasarımın üç ilkesi

### 1. Ontoloji tek gerçek kaynağıdır (single source of truth)

Fiyat, haber, makro veri, şirket, token — hepsi aynı ontoloji içinde birinci sınıf
nesneler. Analiz ajanları veriye doğrudan API'lerden değil, ontolojiden erişir. Böylece:

- Yeni bir varlık sınıfı veya alan eklemek şema migration'ı gerektirmez
- Her verinin kaynağı, güvenilirliği ve **ne zaman öğrenildiği** kayıtlıdır
- "Şu anda" değil, "şu tarihte bildiğimiz haliyle" sorgulanabilir

### 2. Karar, uygulamadan önce mühürlenir

Bir karar `PROPOSED` durumuna geçtiği anda tezi, kanıtları, güven skoru ve
**onu çürütecek koşullar** (invalidation criteria) yazılır ve değiştirilemez hale gelir.
Sonuç ne olursa olsun bu kayıt aynen durur. Bu, LLM'in sonradan kendi kararını
rasyonalize etmesini engelleyen tek yapısal savunmadır.

### 3. Risk katmanı LLM değildir

LLM asla emir gönderemez. LLM `intent` (niyet) üretir; deterministik Java kodu bu niyeti
limitlere karşı doğrular, veto eder veya boyutlandırır; emir gönderme yetkisi sadece
execution modülündedir. Prompt injection, halüsinasyon veya model regresyonu durumunda
sermayeyi koruyan sınır burasıdır.

---

## Alınan kararlar

| Konu | Karar | ADR |
|---|---|---|
| Ontoloji deposu | Postgres üzerinde bitemporal EAV; meta + instance ayrımı, JSONB current-state projeksiyonu | [0001](adr/0001-ontoloji-bitemporal-eav.md) |
| Runtime | Sadece Java (Spring Boot). Python quant sidecar yok. | [0002](adr/0002-sadece-java-runtime.md) |
| Veritabanı barındırma | AWS RDS/Aurora PostgreSQL. TimescaleDB yok; native declarative partitioning + kendi rollup job'larımız. | [0003](adr/0003-rds-postgres-timescaledb-yok.md) |
| Faz-1 trading kapsamı | Küçük sermaye ile **canlı** Binance Spot. Futures/kaldıraç yok. | [0004](adr/0004-canli-spot-kucuk-sermaye.md) |
| Event backbone | Modüler monolit + Spring Modulith event'leri + transactional outbox. Kafka yok (şimdilik). | [0005](adr/0005-modular-monolith-outbox.md) |

---

## Bu kararların doğrudan sonuçları

**"Sadece Java" ne getirir, ne götürür?**

Getirisi beklenenden büyük: backtest motoru ile canlı sistem **aynı kod yolunu** kullanır.
Python sidecar'lı mimarilerde klasik hata, backtest'teki strateji kodu ile prod'daki
strateji kodunun zamanla ayrışmasıdır — bizde bu ayrışma mümkün değil, çünkü backtest
"ontolojinin geçmiş bir anındaki hâli üzerinde decision engine'i koşturmak"tan ibaret.

Götürüsü: keşifsel araştırma (Jupyter tarzı hızlı deneme) ve derin öğrenme ekosistemi.
Bunu kabul ediyoruz çünkü bu sistemdeki "öğrenme"nin ezici çoğunluğu gradient descent
değil — hafıza, kalibrasyon ve playbook evrimi (bkz. [07](07-surekli-ogrenme.md)).
İstatistiksel kalibrasyon modeli için Java tarafında Tribuo yeterli. Gerçekten model
eğitimi gerekirse offline yapılır, prod'a sadece katsayılar taşınır.

**"RDS + Timescale yok" ne getirir, ne götürür?**

Yedekleme, failover ve patch yönetimi AWS'te kalır. Karşılığında 1m mumlardan
5m/15m/1h/4h/1d rollup'larını üreten job'ları kendimiz yazarız. Bu ~300 satır kod ve
dikkatli bir "yarım kalmış mum" (`is_final=false`) yönetimi demek — yönetilebilir.
Detay: [04-veri-katmani.md](04-veri-katmani.md).

**"Canlı küçük sermaye" ne getirir, ne götürür?**

Gerçek slippage, gerçek fee, gerçek partial-fill, gerçek rate limit — simülasyonda
doğru modellenmesi zor olan her şey ilk günden görünür. Karşılığında kalibre olmamış
bir sistem gerçek para riske eder. Bunu telafi etmek için sermaye zarfı ve
her deploy öncesi shadow-mode kapısı zorunlu: [06-risk-ve-execution.md](06-risk-ve-execution.md).

---

## Kapsam dışı (bilinçli olarak)

- **Yüksek frekanslı işlem.** LLM çağrısı saniyeler sürer ve para eder; karar döngümüz
  5m–4h aralığında. Tick seviyesinde tepki veren bir sistem tasarlamıyoruz.
- **Kaldıraç ve futures.** Faz-1'de yok. Likidasyon riski, kalibre olmamış bir karar
  sistemiyle birleştiğinde kabul edilemez.
- **Çoklu kullanıcı / SaaS.** Tek kullanıcılı, tek portföylü. Veri modeli çok-portföye
  hazır tutulacak ama UI/auth bu varsayımla basitleştirilecek.
- **Yatırım tavsiyesi üretimi.** Sistem dışarıya sinyal yayınlamaz.
- **Otomatik para çekme.** Borsa API anahtarında withdraw yetkisi kalıcı olarak kapalı.

---

## Başarı ölçütleri

Kâr, faz-1'in birincil ölçütü **değil**. Sırasıyla:

1. **Kalibrasyon.** LLM %70 güven dediğinde gerçekten ~%70 tutuyor mu? Ölçüm: Brier skoru
   ve reliability diagram. Kalibre olmayan bir sistemin kârı şanstır.
2. **Denetlenebilirlik.** Rastgele seçilen bir kararın tam gerekçesi, o anki bilgi durumu
   ve sonucu 30 saniyede görülebiliyor mu?
3. **Risk disiplini.** Tanımlı limitlerin hiçbiri hiçbir zaman aşılmadı mı? Bir limit ihlali,
   pozitif getiriden daha ciddi bir bulgudur.
4. **Öğrenme sinyali.** Kapanan karar sayısı arttıkça kalibrasyon iyileşiyor mu?
5. **Ve ancak bunlardan sonra:** risk-ayarlı getiri (Sharpe, max drawdown, buy-and-hold'a
   karşı fark).
