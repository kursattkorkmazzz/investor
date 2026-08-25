# ADR-0008 — LLM katmanı: LangChain4j

**Durum:** Kabul edildi · 2026-08-25 · Faz 3'te gerçeklendi · [ADR-0002](0002-sadece-java-runtime.md)'yi
tamamlar, [09-tech-stack.md](../09-tech-stack.md)'teki "doğrudan Anthropic Java SDK" tercihini değiştirir

## Bağlam

Tech stack başlangıçta LLM erişimi için resmî Anthropic Java SDK'sını doğrudan kullanmayı
öngörüyordu; gerekçe, prompt caching kırılma noktalarının konumlandırılması, yapılandırılmış
çıktı, adaptive thinking, `effort` seviyesi ve token/maliyet muhasebesine gecikmesiz erişimdi.

Proje sahibi LLM katmanı için LangChain4j kullanılmasını istedi ve başlangıç için
LangChain4j'in anahtar gerektirmeyen **demo uç noktasının** kullanılmasını belirtti.

## Karar

LLM erişimi **LangChain4j 1.19.0** (`dev.langchain4j:langchain4j`,
`dev.langchain4j:langchain4j-open-ai`) üzerinden. Varsayılan yapılandırma LangChain4j'in
demo ucunu gösteriyor:

```
investor.llm.base-url = http://langchain4j.dev/demo/openai/v1
investor.llm.api-key  = demo
investor.llm.model    = gpt-4o-mini
```

Anahtar gerektirmediği için sistem, hesap açılmadan uçtan uca çalışıyor. Demo ucu hız
sınırlı ve garantisiz; üretimde `base-url` ve `api-key` ezilmeli.

**Kritik kısıt:** LangChain4j doğrudan kullanılmaz, kendi portlarımızın **arkasında** durur.
Kütüphane tiplerine dokunan yalnızca iki sınıf var — `LangChain4jLlmClient` ve
`SchemaTranslator`. Bunu build zamanında da zorluyoruz: `backend/llm` modülünde LangChain4j
`implementation` bağımlılığı, `api` değil; başka bir modül LangChain4j tipine erişmeye
kalkarsa derleme kırılır.

Sebep: LangChain4j bir soyutlama katmanı ve soyutlamalar eskir. Kod tabanının tamamı
onun tiplerine bağlanırsa, sağlayıcı ya da kütüphane değişimi her dosyaya dokunur.

## Gerçekleme sırasında öğrenilenler

Faz 3'te JAR'ların içine bakılarak ve WireMock'a karşı koşturularak doğrulanan üç şey —
ikisi ilk yazımdaki varsayımı düzeltiyor:

**1. Şema sunucuda zorlanmıyor.** LangChain4j `response_format.json_schema` içinde
`"strict": false` gönderiyor ve `additionalProperties` alanını hiç yazmıyor. Yani gönderilen
şema **bir güvence değil, güçlü bir ipucu**: model şemayı ihlal edebilir, fazladan alan
ekleyebilir, sayısal sınırların dışına çıkabilir. OpenAI'ın gerçek katı modu her alanın
`required` olmasını şart koşuyor; isteğe bağlı alanlarımız olduğu için ve modeli
dolduramadığı bir alanı doldurmaya zorlamak uydurulmuş değerden başka bir şey üretmeyeceği
için katı moda geçmiyoruz.

Sonuç: zorlamayı yapan tek yer kendi `ResponseValidator`'ımız. Güvenlik modeli buna
dayanıyor ve bu davranış `LlmPipelineGateTest` tarafından sabitlendi — LangChain4j
varsayılanı değişirse test haber verecek.

**2. Token muhasebesi sanılandan iyi.** İlk yazımda "soyutlamanın verdiğiyle sınırlı"
denmişti; `OpenAiTokenUsage` aslında `inputTokensDetails().cachedTokens()` ve
`outputTokensDetails().reasoningTokens()` veriyor. Önbellekli girdi ayrı fiyatlanabiliyor,
görünmez akıl yürütme tokenları sayılabiliyor. Maliyet muhasebesi için ham cevaba inmek
gerekmedi.

**3. `langchain4j-spring-boot-starter`'a ihtiyaç olmadı.** Starter 0.36.2'de kalmış,
çekirdek 1.19.0 — aradaki fark Boot 4 uyumu açısından risk. Bean'ler `LlmConfiguration`'da
elle tanımlandı; toplam ~40 satır ve starter'ın çözdüğü hiçbir sorunu yaşamadık. Starter
bağımlılık listesinde yok.

## Sonuçlar

**Olumlu**
- Sağlayıcı soyutlaması hazır: model değişimi tek yapılandırma satırı
- Demo ucu sayesinde anahtarsız başlangıç
- Token/maliyet muhasebesi (önbellek dahil) kütüphaneden çıkıyor
- LangChain4j iki sınıfa hapsedildi, sınır build zamanında zorlanıyor

**Olumsuz — bilinçli kabul edilenler**
- **Yeni özellikler gecikmeli gelir.** Prompt caching kırılma noktası konumlandırma,
  adaptive thinking ve `effort` seviyesi gibi maliyet ve kalite açısından belirleyici
  ayarlar, sağlayıcı SDK'sında çıktıktan sonra soyutlama katmanına ulaşır. Prompt
  caching bu sistemde ~%40 maliyet farkı demek ([05](../05-analiz-ajanlari.md) bütçe
  tablosu); kütüphane desteklemiyorsa o tasarruf gecikir.
- **Katı şema zorlaması yok** (yukarıda). Doğrulama katmanı bunu telafi ediyor ama
  telafi, sunucu tarafı zorlamanın yerini tam tutmuyor: model boş ya da bozuk cevap
  verdiğinde çağrı boşa gidiyor ve tokenı harcanmış oluyor.
- **Jackson 2 bağımlılığı geliyor.** LangChain4j Jackson 2 kullanıyor, uygulama Jackson 3
  (`tools.jackson`) üzerinde. Paket adları farklı olduğu için yan yana çalışıyorlar, ama
  classpath'te iki Jackson var.

**Telafi:** Port sınırı sayesinde bu maddelerin hiçbiri kilitlenme değil. Prompt caching
ya da katı şema darboğaz olursa, `LlmClient`'ın LangChain4j gerçeklemesi doğrudan SDK
gerçeklemesiyle değiştirilir; çağıran kod bunu görmez.

## Bu ortamda doğrulanamayan

`langchain4j.dev` bu geliştirme ortamından ağ politikasıyla engelli (403). Demo ucunun
kendisi hiç çağrılmadı; hat, OpenAI chat-completions protokolünü konuşan yerel bir
WireMock'a karşı doğrulandı. Kanıtlanan şey sözleşme uyumu — giden isteğin doğru
biçimlendiği ve dönen cevabın doğru ayrıştırıldığı. Demo ucunun çalıştığı kullanıcının
makinesinde denenmeli.

## Yeniden değerlendirme koşulları

- Prompt caching ya da benzer bir maliyet özelliği kütüphanede yoksa ve aylık LLM
  maliyeti bütçeyi zorluyorsa
- Şema ihlali oranı ölçülebilir biçimde yükselirse (kırpma sayacı `llm_call.clamped_fields`)
- Karar başına token/maliyet muhasebesi soyutlamadan çıkarılamıyorsa
