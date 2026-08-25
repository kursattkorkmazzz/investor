# ADR-0002 — Runtime: sadece Java, Python quant sidecar yok

**Durum:** Kabul edildi · 2026-08-24

## Bağlam

Sistem hem LLM orkestrasyonu hem quant iş (indikatör hesabı, backtest, istatistik,
kalibrasyon modeli) yapacak. Yaygın yaklaşım, quant işi Python'a vermek ve iki servisi
bir RPC sınırıyla ayırmak — çünkü pandas / TA-Lib / vectorbt ekosistemi güçlü.

## Karar

Her şey Java'da. Python sidecar yok.

- Teknik analiz: **ta4j**
- İstatistik ve kalibrasyon: **Tribuo** + SQL
- Backtest: kendi motorumuz, canlı sistemin kod yolunu kullanarak
- LLM: **LangChain4j**, kendi `LlmClient` port'umuzun arkasında (bkz. [ADR-0008](0008-langchain4j.md); ilk yazımda Anthropic Java SDK öngörülüyordu)

## Sonuçlar

**Olumlu**
- **Tek kod yolu.** Backtest, `SimulatedExchange` dışında canlı sistemle birebir aynı
  kodu koşturuyor. İki dilli mimarilerdeki klasik felaket — backtest stratejisi ile
  prod stratejisinin zamanla ayrışması — yapısal olarak imkânsız.
- Tek dil, tek build, tek test altyapısı, tek dağıtım birimi
- RPC sınırı yok: serileştirme maliyeti, sürüm uyumsuzluğu, ayrı gözlemlenebilirlik yok
- Sanal iş parçacıkları ajan paralelliğini ucuza çözüyor

**Olumsuz**
- Keşifsel araştırma zayıf. Jupyter'da bir fikri 10 dakikada denemek yerine test yazmak gerekiyor.
- Derin öğrenme pratikte kapalı. Tribuo klasik ML için yeterli, ötesi değil.
- ta4j, pandas'ın esnekliğine sahip değil; alışılmadık bir indikatör gerekirse elle yazılır.
- Backtest motorunu kendimiz yazıyoruz (~1–2 haftalık iş)

**Neden bu takas kabul edilebilir:** Bu sistemdeki "öğrenme"nin neredeyse tamamı
hafıza, kalibrasyon ve playbook evrimi — gradient descent değil (bkz.
[07](../07-surekli-ogrenme.md)). Model eğitiminin merkezî olmadığı bir mimaride
Python'un asıl avantajı devreye girmiyor.

## Değerlendirilen alternatifler

**Java çekirdek + Python quant sidecar.** Her dil güçlü olduğu yerde çalışırdı.
Tek kod yolu garantisinin kaybı ve iki runtime'ın operasyon yükü ağır bastı.

**Python ağırlıklı, Java sadece ontoloji + API.** Spring Boot tercihini büyük ölçüde
geri alırdı.

## Yeniden değerlendirme koşulları

- Derin öğrenme veya ağır sayısal optimizasyon gerçekten gerekirse → **offline araç**
  olarak Python; prod karar yolunda değil, sadece katsayı üreten bir adım
- ta4j'nin karşılamadığı indikatör ihtiyacı sistematik hale gelirse
- Araştırma hızı somut bir darboğaz olarak ölçülebilir hale gelirse (JBang/JShell
  ile hafifletilebilir mi önce denenir)
