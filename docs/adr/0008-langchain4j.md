# ADR-0008 — LLM katmanı: LangChain4j

**Durum:** Kabul edildi · 2026-08-25 · [ADR-0002](0002-sadece-java-runtime.md)'yi tamamlar,
[09-tech-stack.md](../09-tech-stack.md)'teki "doğrudan Anthropic Java SDK" tercihini değiştirir

## Bağlam

Tech stack başlangıçta LLM erişimi için resmî Anthropic Java SDK'sını doğrudan kullanmayı
öngörüyordu; gerekçe, prompt caching kırılma noktalarının konumlandırılması, yapılandırılmış
çıktı, adaptive thinking, `effort` seviyesi ve token/maliyet muhasebesine gecikmesiz erişimdi.

Proje sahibi LLM katmanı için LangChain4j kullanılmasını istedi ve API erişimini kendisi
sağlayacağını belirtti.

## Karar

LLM erişimi **LangChain4j** (`dev.langchain4j:langchain4j` 1.19.0) üzerinden,
`langchain4j-anthropic` sağlayıcısıyla. Faz 3'te gerçeklenecek.

Kritik kısıt: LangChain4j doğrudan kullanılmaz, kendi portlarımızın **arkasında** durur.
Faz 2'de yazılan `NewsExtractor` bu tasarımın ilk örneği — kural tabanlı bir varsayılanı
var ve LangChain4j gerçeklemesi onun yerine geçtiğinde çağıran hiçbir kod değişmeyecek.
Faz 3'te eklenecek `LlmClient` portu da aynı şekilde.

Sebep: LangChain4j bir soyutlama katmanı ve soyutlamalar eskir. Kod tabanının tamamı
onun tiplerine bağlanırsa, sağlayıcı ya da kütüphane değişimi her dosyaya dokunur.
Port arkasında durduğunda değişecek yer tek sınıf olur.

## Sonuçlar

**Olumlu**
- Sağlayıcı soyutlaması hazır: model değişimi tek yapılandırma satırı
- Yapılandırılmış çıktı, araç çağırma ve retry gibi tekrarlayan işler kütüphanede
- Ekosistem tanıdık; belge ve örnek bolluğu

**Olumsuz — bilinçli kabul edilenler**
- **Yeni özellikler gecikmeli gelir.** Prompt caching kırılma noktası konumlandırma,
  adaptive thinking ve `effort` seviyesi gibi maliyet ve kalite açısından belirleyici
  ayarlar, sağlayıcı SDK'sında çıktıktan sonra soyutlama katmanına ulaşır. Prompt
  caching bu sistemde ~%40 maliyet farkı demek ([05](../05-analiz-ajanlari.md) bütçe
  tablosu); kütüphane desteklemiyorsa o tasarruf gecikir.
- **Token ve maliyet muhasebesi soyutlamanın verdiğiyle sınırlı.** Karar başına maliyet
  izleme ([03](../03-decision-engine.md)) için gereken alanlar eksikse, ham cevaba
  inmemiz gerekebilir.
- **Spring Boot 4 uyumu doğrulanmadı.** `langchain4j-spring-boot-starter` 0.36.2'de,
  çekirdek ise 1.19.0 — starter geride. Boot 4 ile uyumu Faz 3'ün ilk işi olarak
  doğrulanacak; sorun çıkarsa starter yerine bean'leri elle tanımlarız.

**Telafi:** Port sınırı sayesinde bu maddelerin hiçbiri kilitlenme değil. Prompt caching
ya da maliyet muhasebesi darboğaz olursa, `LlmClient`'ın LangChain4j gerçeklemesi
doğrudan SDK gerçeklemesiyle değiştirilir; çağıran kod bunu görmez.

## Yeniden değerlendirme koşulları

- Prompt caching ya da benzer bir maliyet özelliği kütüphanede yoksa ve aylık LLM
  maliyeti bütçeyi zorluyorsa
- Boot 4 uyumu için sürdürülemez ölçüde ara katman yazmak gerekiyorsa
- Karar başına token/maliyet muhasebesi soyutlamadan çıkarılamıyorsa
