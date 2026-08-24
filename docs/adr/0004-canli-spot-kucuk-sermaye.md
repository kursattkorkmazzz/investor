# ADR-0004 — Faz-1: küçük sermaye ile canlı Binance Spot

**Durum:** Kabul edildi · 2026-08-24

## Bağlam

Faz-1'de üç seçenek vardı: (a) paper trading / testnet, (b) küçük sermaye ile canlı
spot, (c) spot + futures.

Kayıt edilen teknik itiraz: LLM'in beyan ettiği güven skorları henüz kalibre değil.
Bir sistem "%85 eminim" dediğinde bunun gerçekten %85 tuttuğu ancak birkaç yüz
kapanmış karardan sonra bilinebilir. Kalibrasyon oluşmadan gerçek sermaye riske
giriyor.

Bunun karşısında duran gerçek: simülasyonda doğru modellenmesi zor olan şeyler —
kayma, kısmi dolum, ücret etkisi, rate limit davranışı, emir reddi, borsa gecikmesi —
canlıda ilk günden görünür. Paper trading, bu sınıftaki hataları sistematik olarak gizler.

## Karar

Küçük sermaye ile **canlı** Binance Spot. Futures ve kaldıraç yok.

Karar, aşağıdaki telafi mekanizmalarıyla birlikte alınmıştır ve bunlar kararın
ayrılmaz parçasıdır:

1. **Sermaye zarfı.** Borsadaki toplam bakiye kaybı göze alınabilir bir tavanla sınırlı.
   Kâr biriktikçe otomatik büyümez; büyütmek açık bir insan kararı.
2. **Withdraw yetkisi kapalı** API anahtarı + IP whitelist. Anahtar sızsa bile para
   dışarı çıkamaz.
3. **Katmanlı deterministik limitler.** İşlem başı risk, pozisyon tavanı, toplam
   maruziyet, korelasyonlu maruziyet, günlük/haftalık zarar, günlük emir sayısı.
4. **Kill-switch.** Otomatik tetikleyiciler + manuel düğme. Kill-switch açık
   pozisyonları kapatmaz (panik satışı riski) — bekleyen emirleri iptal eder ve
   yeni karar üretimini durdurur.
5. **Borsa taraflı stop.** Pozisyon açılır açılmaz OCO gönderilir. OCO başarısız
   olursa pozisyon derhal kapatılır — korumasız pozisyon taşınmaz.
6. **Kalibrasyon oluşana kadar en muhafazakâr boyutlandırma.** 100 kapanmış karar
   eşiğine kadar güven katsayısı sabit ve düşük.
7. **Faz kapıları.** Testnet doğrulaması, 7 gün temiz reconciliation, kill-switch
   tatbikatı, 14 günlük shadow — hepsi geçilmeden canlı sermaye açılmaz.
8. **Her deploy için shadow kapısı.** `analysis`, `decision-engine` veya `risk`
   modülüne dokunan her değişiklik, canlıya çıkmadan önce 72 saat / 30 karar shadow
   koşar. Tek bir limit ihlali deploy'u durdurur.

## Sonuçlar

**Olumlu**
- Kayma, ücret, kısmi dolum, rate limit — gerçek davranış ilk günden ölçülüyor
- Sonuç verisi gerçek; kalibrasyon gerçek dünyada ölçülüyor
- Execution ve reconciliation hataları erken ve ucuz bulunuyor

**Olumsuz**
- Kalibre olmamış bir sistem gerçek para riske ediyor
- Zarf küçük olduğu için LLM + altyapı maliyeti getiriye göre yüksek — faz-1
  ekonomik olarak kârlı olmayacak (bkz. [05](../05-analiz-ajanlari.md) bütçe tablosu)
- Risk ve execution katmanı faz-1'de tam olgunlukta yazılmak zorunda; kısayol yok

## Yeniden değerlendirme koşulları

- Shadow fazında limit ihlali görülürse → canlıya çıkış ertelenir, önce sebep düzeltilir
- Canlı fazda tek bir reconciliation uyuşmazlığı → sistem durur, sebep bulunmadan açılmaz
- Sermaye artışı, ancak 30 gün temiz canlı çalışma ve ölçülebilir kalibrasyon sonrası
- Futures/kaldıraç, en erken kalibrasyon oturduktan ve pozitif risk-ayarlı getiri
  gösterildikten sonra ayrı bir ADR ile değerlendirilir
