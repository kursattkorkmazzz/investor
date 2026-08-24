# 08 — Frontend

React + TypeScript. Amaç bir "trading terminali" değil — **bir denetim ve anlama aracı.**
Ekranların çoğu al-sat için değil, sistemin ne düşündüğünü ve neden yanıldığını görmek için.

---

## Ekranlar

| Ekran | Amaç |
|---|---|
| **Dashboard** | Anlık durum: PnL, açık pozisyonlar, limit kullanımı, kill-switch |
| **Decision Inspector** | Tek bir kararın tam anatomisi — ürünün merkezi ekranı |
| **Decisions** | Karar akışı; duruma, sembole, hükme, güvene göre filtreleme |
| **Ontology Explorer** | Nesne arama, detay, alan geçmişi zaman çizelgesi, ilişki grafiği |
| **Ontology Modeler** | Tip / alan / ilişki tanımlama ve şema sürüm geçmişi |
| **Calibration** | Reliability diagram, Brier trendi, kanıt etkinliği |
| **Market** | Mum grafiği + karar işaretleri overlay |
| **Backtest** | Aralık seç, replay/fresh koş, sonuçları canlıyla karşılaştır |
| **Risk & Health** | Limit kullanımı, reconciliation durumu, veri tazeliği, LLM maliyeti |

---

## Decision Inspector

Bu ekran, decision engine'in tuttuğu kaydın karşılığıdır. Kullanıcının 30 saniyede
şunu görebilmesi hedefi: *bu kararı neden verdi, neye dayandı, ne oldu, yanıldı mı.*

```
┌──────────────────────────────────────────────────────────────────────┐
│  BTCUSDT · BUY · %2.5 pozisyon            [CLOSED] [verdict: LUCKY]  │
│  24 Ağu 2026 12:00 · güven 0.72 (kalibre 0.61) · playbook v7         │
├──────────────────────────────────────────────────────────────────────┤
│  TEZ                                                                 │
│  "ETF akışları üç gündür pozitif; RSI aşırı satımdan dönüyor;        │
│   makro rejim RISK_ON'a geçti."                                      │
├──────────────────────────────────────────────────────────────────────┤
│  KANITLAR                          yön      ağırlık                  │
│  ● TECHNICAL  RSI(14)=28.4         DESTEK    ████░░ 0.60            │
│  ● NEWS       ETF girişi $240M     DESTEK    █████░ 0.75            │
│  ● MACRO      DXY 3 günlük düşüş   DESTEK    ███░░░ 0.45            │
│  ● ONCHAIN    borsa rezervi arttı  ÇELİŞKİ   ██░░░░ 0.30            │
│  ● MEMORY     benzer 6 karar: 2/6  ÇELİŞKİ   ███░░░ 0.40            │
│    → her satır ontolojideki kaynağa tıklanabilir                     │
├──────────────────────────────────────────────────────────────────────┤
│  İTİRAZLAR                                                           │
│  ⚠ DEVILS_ADVOCATE (WARNING): "Kanıtların üçü de aynı makro          │
│    hikâyeden türüyor — bağımsız görünüyorlar ama değiller."          │
│    çözüm: pozisyon %2.5'e düşürüldü                                  │
├──────────────────────────────────────────────────────────────────────┤
│  ÇÜRÜTME KOŞULLARI            durum                                  │
│  · fiyat < 65.800             tetiklenmedi                           │
│  · 12 saat içinde hareket yok tetiklenmedi                           │
│  · ETF akışı negatife dönerse ✗ TETİKLENDİ (T+9s)                   │
├──────────────────────────────────────────────────────────────────────┤
│  ZAMAN ÇİZELGESİ                                                     │
│  12:00 PROPOSED  12:01 CHALLENGED  12:01 RISK_REVIEW                │
│  12:02 APPROVED  12:04 SUBMITTED   12:06 OPEN                       │
│  18:12 CLOSING   18:12 CLOSED      18:20 EVALUATED                  │
├──────────────────────────────────────────────────────────────────────┤
│  SONUÇ                                                               │
│  net PnL +$17.58 (+3.52%)   benchmark +1.10%                        │
│  MAE −1.12%   MFE +4.21%   tutma 6s12dk   kayma 4.2 bps             │
│  çıkış: TARGET_HIT                                                   │
│                                                                      │
│  HÜKÜM: LUCKY — para kazandırdı ama tez çürümüştü.                  │
│  ETF akışı ikinci gün negatife döndü; fiyat başka sebeple yükseldi. │
│  → Lesson-42'ye katkı: "tek makro hikâyeden türeyen kanıtlar         │
│     bağımsız sayılmamalı"                                            │
└──────────────────────────────────────────────────────────────────────┘
```

`LUCKY` hükmünün ekranda bu kadar görünür olması bilinçli. Kâr eden ama tezi çürüyen
bir karar, zarar eden bir karardan daha tehlikelidir — çünkü yanlış dersi öğretir.

---

## Ontology Explorer

İki bileşen taşıyor:

**Alan geçmişi zaman çizelgesi.** Bir nesnenin herhangi bir alanına tıklandığında
tüm değer geçmişi açılır: değer, geçerlilik aralığı, ne zaman öğrenildiğimiz, kaynak,
güven skoru, hangi commit. Geri çekilmiş (`retracted`) kayıtlar üstü çizili gösterilir —
gizlenmez. Sistemin bir dönem yanlış bilgiyle çalıştığı görülebilir olmalı.

**"As-of" kaydırıcısı.** Sayfanın üstünde bir zaman kaydırıcısı; geçmiş bir ana
çekildiğinde tüm ekran o anda **bildiğimiz** hâle döner. Bir kararı denetlerken
"o gün elimizde ne vardı" sorusunun görsel cevabı bu.

İlişki grafiği n-hop gezinme için; düğüm sayısı sınırlanır (varsayılan 3 hop / 200 düğüm),
aksi halde ekran okunmaz hale gelir.

---

## Dinamik şema, dinamik UI

Ontoloji çalışma zamanında değiştiği için frontend sabit form yazamaz. Akış:

1. `GET /api/ontology/types` → tipler, alanlar, kısıtlar, gösterim sırası
2. Alan tipine göre bileşen seçilir (`STRING`→input, `ENUM`→select, `TIMESTAMP`→tarih
   seçici, `REFERENCE`→nesne arayıcı)
3. Doğrulama kuralları `constraints` JSON'undan Zod şemasına çevrilir
4. Sorgular tek `POST /api/ontology/query` uç noktasına JSON DSL olarak gider

Bu, "yeni alan ekledim, frontend'i de güncellemem lazım" döngüsünü ortadan kaldırıyor —
ontoloji mimarisinin somut karşılığı.

---

## Teknik seçimler

| Konu | Seçim | Gerekçe |
|---|---|---|
| Derleme | Vite | Hızlı, yapılandırması az |
| Yönlendirme | TanStack Router | Tip güvenli rotalar ve arama parametreleri |
| Sunucu durumu | TanStack Query | Cache, yeniden getirme, iyimser güncelleme |
| İstemci durumu | Zustand | Küçük; global durum zaten az |
| Formlar | React Hook Form + Zod | Dinamik şemadan doğrulama üretimi |
| Stil | Tailwind + shadcn/ui | Hızlı, tutarlı, tema desteği |
| Grafik (fiyat) | lightweight-charts | Mum grafikleri için hafif ve olgun |
| Grafik (analitik) | Recharts | Kalibrasyon, PnL, dağılım grafikleri |
| Graf görselleştirme | React Flow | İlişki grafiği |
| Tablolar | TanStack Table | Sanallaştırma, sıralama, filtreleme |
| API istemcisi | OpenAPI'den üretilir | Backend DTO değişince derleme hatası verir |
| Test | Vitest + Testing Library + Playwright | |

### Canlı güncelleme

WebSocket yerine **SSE** (Server-Sent Events). Tek yönlü akış yeterli — komutlar
zaten REST üzerinden gidiyor. SSE'nin otomatik yeniden bağlanması ve proxy uyumu
bu iş için WebSocket'ten daha az bakım istiyor.

Kanallar: `/api/stream/decisions`, `/api/stream/portfolio`, `/api/stream/health`.

TanStack Query cache'i SSE olaylarıyla güncellenir; ekranlar tam yeniden getirme
yapmadan tazelenir.

### Para gösterimi

Backend tüm parasal değerleri **string** olarak döner (`"67412.30"`), sayı olarak
değil. Sebep: JavaScript'in `number`'ı IEEE 754 double — `0.1 + 0.2 !== 0.3`.
Frontend'de `decimal.js` ile işlenir, `Intl.NumberFormat` ile gösterilir.
Bu, backend'deki `BigDecimal` disiplininin uçtaki karşılığı.

### Erişim

Tek kullanıcılı; OAuth2 + JWT, Spring Security tarafından. Kill-switch ve `PANIC`
düğmeleri ayrıca ikinci bir onay adımı ister.

Kill-switch düğmesi her ekranın üst çubuğunda sabit durur — arandığında bulunması
gereken bir şey değil.
