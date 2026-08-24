# investor

LLM tabanlı, ontoloji-merkezli otonom trading sistemi.

Sistem; temel analiz, teknik analiz, haber akışı ve makro/mikro ekonomik göstergeleri
tek bir **dinamik ontoloji** üzerinde birleştirir; her al-sat kararını gerekçesi,
güven skoru ve sonucuyla birlikte kayıt altına alan bir **decision engine** üzerinden
yürütür; kapanan her karardan öğrenerek kendini kalibre eder.

> **Durum:** Tasarım aşaması. Kod henüz yok — bu repo şu an mimari planı barındırıyor.

---

## Neden bu iki kavram merkezde?

**Ontoloji**, "veri modeli"nin kendisinin de veri olduğu bir katman. Yeni bir varlık
sınıfı (token, hisse, şirket, merkez bankası, haber), yeni bir alan veya yeni bir ilişki
türü eklemek için deploy gerekmiyor. Üstelik her alan değeri zaman içinde versiyonlanıyor:
bir alanı güncellediğinde eskisi silinmiyor, kapanıyor. Bu sayede
_"3 Mart'ta bu kararı verirken elimizde hangi bilgi vardı?"_ sorusu cevaplanabilir hale
geliyor — ki bu, look-ahead bias olmadan backtest yapmanın tek dürüst yolu.

**Decision Engine**, LLM'in ürettiği her kararın tam yaşam döngüsünü yönetir:
ne zaman alındı, hangi kanıtlara dayandı, güven skoru neydi, kim itiraz etti,
nasıl uygulandı, ne oldu, doğru mu çıktı, bundan ne öğrendik. Karar *uygulanmadan önce*
gerekçesiyle birlikte mühürlenir; böylece sonradan hikâye uydurmak (hindsight bias)
yapısal olarak imkânsız hale gelir.

---

## Doküman haritası

| # | Doküman | İçerik |
|---|---|---|
| 00 | [Genel bakış ve kararlar](docs/00-genel-bakis.md) | Kapsam, hedefler, kapsam dışı, alınan kararların özeti |
| 01 | [Mimari](docs/01-mimari.md) | Modüller, veri akışı, karar döngüsü, repo yapısı |
| 02 | [Ontoloji](docs/02-ontoloji.md) | Meta model, bitemporal şema, tam SQL, sorgu desenleri |
| 03 | [Decision Engine](docs/03-decision-engine.md) | Karar yaşam döngüsü, kanıt modeli, kalibrasyon, tam SQL |
| 04 | [Veri katmanı](docs/04-veri-katmani.md) | OHLCV ingest, partitioning, rollup, haber/makro kaynakları |
| 05 | [Analiz ajanları](docs/05-analiz-ajanlari.md) | LLM ajan mimarisi, sorumluluk sınırları, çıktı sözleşmeleri |
| 06 | [Risk ve execution](docs/06-risk-ve-execution.md) | Risk motoru, canlı sermaye güvenlik kapıları, emir yürütme |
| 07 | [Sürekli öğrenme](docs/07-surekli-ogrenme.md) | Üç öğrenme döngüsü, kalibrasyon, playbook evrimi |
| 08 | [Frontend](docs/08-frontend.md) | React ekranları, Decision Inspector, Ontology Explorer |
| 09 | [Tech stack](docs/09-tech-stack.md) | Tam bağımlılık listesi ve her birinin gerekçesi |
| 10 | [Yol haritası](docs/10-yol-haritasi.md) | Fazlar, çıktılar, kapılar, tahmini süreler |

Mimari kararların gerekçeleri: [docs/adr/](docs/adr/)

---

## Tech stack (özet)

- **Backend:** Java 21 · Spring Boot 3 · Spring Modulith · Spring AI · ta4j · Flyway
- **Frontend:** React 19 · TypeScript · Vite · TanStack Query · Tailwind · lightweight-charts
- **Veri:** PostgreSQL (AWS RDS) · pgvector · pg_partman · Redis (ElastiCache/Valkey)
- **Borsa:** Binance Spot (REST + WebSocket), `ExchangePort` arkasında soyutlanmış

Detay ve gerekçeler: [docs/09-tech-stack.md](docs/09-tech-stack.md)

---

## Uyarı

Bu sistem gerçek parayla işlem yapmak üzere tasarlanıyor. Otomatik trading, sermayenin
tamamının kaybıyla sonuçlanabilir. Sistem hiçbir aşamada yatırım tavsiyesi üretmez ve
finansal sonuçların sorumluluğu tamamen kullanıcıya aittir. Canlı sermayeye geçiş
kurallarını [docs/06-risk-ve-execution.md](docs/06-risk-ve-execution.md) belirler; bu
kapılar atlanmaz.
