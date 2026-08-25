# 12 — Deterministik analiz katmanı

`backend/analysis` modülünün LLM'siz yarısı: göstergeler, istatistikler, rejim ve
tetikleyici kapısı. Bu belge **ölçümle düzeltilmiş** tasarım kararlarını anlatıyor.

İlgili: [05 — Analiz ajanları](05-analiz-ajanlari.md) · [11 — LLM katmanı](11-llm-katmani.md)

---

## Bölünme kuralı

| İş | Nerede |
|---|---|
| Gösterge hesabı (RSI, MACD, EMA, ATR, Bollinger, VWAP) | ta4j — deterministik |
| İstatistik (persentil, z-skor, getiri, oynaklık) | Java — deterministik |
| Rejim sınıflandırması | Kural tabanlı Java — deterministik |
| Tetikleyici kapısı | Kural tabanlı Java — deterministik |
| "Bu göstergeler bir arada ne anlatıyor?" | LLM |

LLM'e verilen her sayı, **kaynağı ve hesaplanma yöntemiyle** birlikte gidiyor. Bu yüzden
`IndicatorValue` ve `StatValue` yalnızca değer değil `method` ve `barsUsed`/`sampleSize`
de taşıyor: çıplak bir `rsi14: 28.4` verilseydi model bu sayının hangi zaman diliminden,
kaç mumdan, hangi yumuşatmayla geldiğini varsaymak zorunda kalırdı — ve varsayardı.

---

## Isınma: sessizce yanlış olan sayı

`Ta4jIndicatorService`'in en önemli davranışı burada.

ta4j'ye 14 mumluk bir seride RSI(14) sorarsanız size bir sayı verir. O sayı **hata
değil** — sadece anlamlı değil: Wilder yumuşatması özyinelemeli ve ilk değerler seri
başlangıcının etkisini taşıyor. Aynı şey EMA ve ATR için de geçerli.

Bu tür bir yanlışlık en tehlikeli olanı: istisna fırlatmaz, log'a düşmez, testte kırmızı
yanmaz. Sadece kararı sessizce zehirler.

**Çözüm:** özyinelemeli göstergeler için periyodun **4 katı** mum isteniyor; yoksa
gösterge hiç üretilmiyor ve adı `unavailable` listesine yazılıyor.

> Neden 4: EMA'da seri başlangıcının ağırlığı `(1−α)^n` ile azalıyor, α = 2/(n+1).
> Dört periyot sonunda kalan etki %1'in altına iniyor; üç periyotta ~%5 ve bu bir eşik
> karşılaştırmasını çevirmeye yetiyor.

**Eksik gösterge, yanlış göstergeden iyidir.**

---

## Ölçek bağımsızlığı: her yerde göreli

Sistem boyunca tekrarlanan bir ilke: **hiçbir eşik mutlak değil.**

| Ölçüm | Neden göreli |
|---|---|
| `atrPercent` (ATR / kapanış) | Çıplak ATR, BTC ile bir altcoin arasında kıyaslanamaz. "ATR 2400" modele hiçbir şey ifade etmez. |
| Fiyat şoku eşiği (ATR katı) | %3'lük hareket sakin bir varlıkta şok, oynak bir altcoinde sıradan. |
| Oynaklık rejimi (persentil) | %3 yıllık oynaklık bir hisse için yüksek, bir altcoin için uykudur. |
| MACD anlamlılığı (fiyata oran) | Mutlak bir histogram eşiği varlıklar arası taşınamaz. |

Bu, kapı testiyle **doğrulandı**: aynı istatistiksel dokuya sahip $30.000 ve $0.30
fiyatlı iki varlık, birebir aynı oranda tur açıyor. Eşikleri sembol başına ayarlamak
zorunda kalmıyoruz.

---

## İstatistik: tanım belirsizliğini kapatmak

`Descriptives` ayrı bir sınıf çünkü **aynı tanımın her yerde kullanıldığını garanti
etmek** gerekiyor. "Persentil" kelimesi en az üç farklı şeyi anlatmak için kullanılıyor;
iki yerde iki tanım, aynı sayının iki anlama geldiği bir sistem demek.

Seçilen tanımlar ve gerekçeleri:

- **Persentil sırası:** eşit değerlerin yarısı sayılan sıra — süreksiz serilerde daha kararlı.
- **z-skor:** sapma sıfırken **boş** dönüyor, sıfır değil. Sıfır dönseydi "tam ortalamada"
  gibi okunurdu; doğru okuma "bu seride sapma yok, z-skor anlamsız".
- **Log getiri:** toplanabilir ve simetrik. %50 düşüp %100 çıkmak basit getiride +%50
  görünürken log getiride sıfır.
- **Oynaklık:** yıllıklandırılmış. %2'lik saatlik sapma ile %2'lik günlük sapma aynı şey
  değil; modele ham sapma vermek bu farkı gizler.
- **Hacim z-skoru:** şimdiki mum kendi karşılaştırma penceresinin **dışında**. İçinde
  olsaydı sıçrama ortalamayı yukarı çekerek kendi anomalisini gizlerdi.

---

## Rejim: yavaş karakter

İki eksen — trend ve oynaklık — ayrı tutuluyor. "Yükseliyor" ile "sakin yükseliyor" aynı
pozisyon boyutunu hak etmez; yüksek oynaklıkta aynı stop mesafesi çok daha sık tetiklenir.

`UNKNOWN` gerçek bir durum, tembellik değil: EMA200 ısınmamışsa trend *bilinmiyor*dur.
Bunu `RANGE` saymak "yatay seyrediyor" diye bir iddiada bulunmak olurdu.

### Histerezis — ve neden gerekli olduğunu ölçüm söyledi

İlk sürüm simetrik bir ölü bant kullanıyordu. Ölçüm bunun yetmediğini gösterdi:
**1500 mumda 248 rejim değişimi, yani her 6 mumda bir.** Rejim değişimi pahalı bir LLM
turu açtığı için bu doğrudan para demek.

İki kaynak vardı:

**1. Simetrik eşik.** Bir ölçüm eşiğin etrafında dolaşırken sınıflandırma her mumda taraf
değiştirir. Çözüm asimetri — *bir rejime girmek, o rejimde kalmaktan zordur*:

| Geçiş | Girmek | Kalmak |
|---|---|---|
| Trend | ayrışma > %0.15 | > %0.08 |
| Yüksek oynaklık | persentil > 80 | > 60 |
| Düşük oynaklık | persentil < 20 | < 40 |

Trend histerezisi **yönlü**: yükselişten düşüşe geçerken gevşek eşik uygulanmıyor, yoksa
histerezis amacının tersine çalışırdı.

**2. Kavramsal hata (daha derini).** Rejimin oynaklık ekseni kısa vadeli (24 mum) ölçüye
bağlıydı.

> **Rejim yavaş değişen bir karakterdir; tetikleyici hızlı bir olaydır.**

İkisini aynı ölçüye bağlamak, kısa vadeli bir oynaklık sıçramasını "piyasanın karakteri
değişti" diye okumak demek. Sıçramaların kendisi zaten `PRICE_SHOCK` ve `VOLUME_ANOMALY`
ile yakalanıyor.

Rejim yavaş ölçüye (96 mum ≈ H1'de 4 gün) taşındı. Pencere **taramayla** seçildi:

| Pencere | Savrulma | Not |
|---|---|---|
| 24 | %7.0 | çok hızlı |
| 48 | %3.7 | |
| **96** | **%2.5** | dağılım dengeli |
| 168 | %1.7 | NORMAL sınıfı erimeye başlıyor |

---

## Tetikleyici kapısı: maliyet tasarımının dayanağı

Naif tasarım burada duvara çarpıyor: 8 sembol × 15 dakikada bir tur = günde 768 tur,
tur başına ~$0.40 → **günde $270.**

### İki mekanizma

**1. Geçişler, durumlar değil.** "RSI 28" durumunu tetikleyici saysaydık, düşen bir
piyasada RSI günlerce 30'un altında kalabilir ve her tur açılırdı — kapı tamamen devre
dışı. "RSI aşırı satım bölgesine *girdi*" bir kez tetikler ve bilgi taşır.

İstisna: hacim anomalisi ve fiyat şoku. Bunlar zaten tek mumluk olaylar; geçiş aramak
gereksiz karmaşıklık olurdu.

**2. Anlamlılık eşikleri.** Geçiş tespiti tek başına yetmedi (aşağıda).

### Ölçüm tasarımı üç kez düzeltti

| Sorun | Ölçülen | Düzeltme | Sonra |
|---|---|---|---|
| Rejim savruluyor | `REGIME_CHANGE=248` | Yavaş ölçü + histerezis | 88 |
| MACD sıfırda salınıyor | `MACD_CROSS=121` | Anlamlılık eşiği (fiyatın %0.05'i) | 37 |
| Bollinger ucundan değiyor | `BOLLINGER_BREAKOUT=94` | Bandı %5 aşma şartı | 70 |

**Sonuç: %29.3 → %14.5 açılma oranı ≈ 3.5 tur/gün/sembol.**
Sekiz sembolde ~28 tur/gün; [05](05-analiz-ajanlari.md)'teki ~38 tahmininin altında.

Üçünü de ölçüm buldu. **Kod okuyarak hiçbiri görünmüyordu** — bütün tetikleyiciler tek
tek makul, sorun bir aradaki davranıştaydı.

### Kapı testi ne iddia ediyor

`TriggerGateRateTest` dört şey ölçüyor:

1. Gerçekçi bir seride açılma oranı %20'nin altında (üst sınır: maliyet) ve %0.5'in
   üstünde (alt sınır: hiç açmayan kapı da bozuk).
2. **Hiçbir tetikleyici tek başına baskın değil.** Baskınlık, o tetikleyicinin gürültü
   ürettiğinin işareti oldu — üç kez.
3. Düzgün bir rampada kapı hiç açılmıyor (%0).
4. Ölçek değişmezlik: pahalı ve ucuz varlık aynı oranı veriyor.

Bant kasten geniş: burada doğrulanan bir *doğruluk* değil bir *oran*. Dar bir bant,
gösterge parametrelerindeki her masum değişiklikte kırmızı yanan kırılgan bir test olurdu.

---

## Kalibrasyon borçları

Bu eşiklerin hepsi **sentetik veri üzerinde** seçildi. Gerçek piyasa verisiyle yeniden
ölçülmeli — dedup eşiği ([04](04-veri-katmani.md)) ile aynı kategoride kayıtlı bir borç:

| Parametre | Değer | Nerede |
|---|---|---|
| Trend giriş/çıkış | %0.15 / %0.08 | `RuleRegimeClassifier` |
| Oynaklık bantları | 20/40, 80/60 | `RuleRegimeClassifier` |
| Yavaş oynaklık penceresi | 96 mum | `DefaultStatsService` |
| MACD anlamlılık | fiyatın %0.05'i | `DefaultTriggerGate` |
| Bollinger aşma payı | %B ± 0.05 | `DefaultTriggerGate` |
| Fiyat şoku | 2 × ATR% | `DefaultTriggerGate` |
| Hacim anomalisi | z ≥ 3.0 | `DefaultTriggerGate` |

Kapının kendisi de ölçülecek: hangi tetikleyicinin açtığı turların iyi kararlar ürettiği
sonradan sorulacak ve fayda üretmeyen tetikleyici budanacak — her bileşen kendi faydasını
kanıtlamak zorunda ([07](07-surekli-ogrenme.md)).

---

## ta4j sürüm kısıtı

**ta4j 0.22.0'a sabitlendi.** 0.22.1 ve sonrası Java 25 bytecode üretiyor (major 69),
projenin toolchain'i Java 21. Kullandığımız yapıcılar 0.24.1 ile birebir aynı, dolayısıyla
sürüm yükseltmesi toolchain Java 25'e taşındığında tek satırlık bir değişiklik olacak.

ta4j `backend/analysis`'in `internal` paketinde kalıyor; tipleri porta sızmıyor ve bu
sınır Gradle'da `implementation` bağımlılığıyla build zamanında zorlanıyor —
LangChain4j'de olduğu gibi ([ADR-0008](adr/0008-langchain4j.md)).
