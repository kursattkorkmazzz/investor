# ADR-0007 — Spring Boot 4

**Durum:** Kabul edildi · 2026-08-24 · [ADR-0002](0002-sadece-java-runtime.md)'yi tamamlar

## Bağlam

Plan "Spring Boot 3.x" diyordu. Kurulum sırasında güncel kararlı sürümün Spring Boot 4.1
olduğu görüldü (Spring Framework 7, Jakarta EE 11 üzerinde). Spring Modulith'in eşleşen
sürümü 2.1.

## Karar

Spring Boot 4.1 + Spring Modulith 2.1, Java 21 toolchain ile.

## Sonuçlar

Boot 4, planın yazıldığı sırada bilinmeyen üç yapısal değişiklik getirdi. Üçü de
gerçekleme sırasında karşımıza çıktı; burada kayıtlı olmaları sonraki modüllerde aynı
duvara tekrar çarpılmasını önlüyor.

**1. Jackson 3.** Boot 4, varsayılan JSON eşleyicisi olarak `tools.jackson.databind`
kullanıyor; `com.fasterxml.jackson.databind` sınıf yolunda geçişli olarak bulunsa bile
autoconfiguration onu üretmiyor. İki davranış farkı önemli:

- `FAIL_ON_NULL_FOR_PRIMITIVES` varsayılan olarak **açık**: istek gövdesinde atlanan bir
  `boolean` alan hata veriyor. İstek DTO'larında mantıksal ve sayısal alanlar bu yüzden
  kutulu (`Boolean` / `Integer`) — bu aynı zamanda "belirtilmedi" ile "false" ayrımını
  kodda görünür kılıyor.
- `JsonNode.asText()` yerine `asString()`.

Ontoloji modülü ayrıca kendi eşleyicisini kuruyor, uygulamanınkine bağlanmıyor: ontoloji
JSON'u API cevabı değil, saklanan veri. Sunum ayarları değiştiğinde aynı değerin iki kez
farklı serileşmesi, "değişmedi" karşılaştırmasını ve denetim defterini sessizce bozardı.

**2. Autoconfiguration teknoloji başına ayrı modüllere bölündü.** `spring-boot-autoconfigure`
artık her şeyi taşımıyor:
- Flyway için `org.springframework.boot:spring-boot-flyway`
- MockMvc testleri için `org.springframework.boot:spring-boot-webmvc-test`
  (sınıf `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`)

**3. Bean sırası.** `JdbcClient` bean'i Flyway migration'ından önce hazır olabiliyor.
`SchemaRegistry` bu yüzden şemayı ilk erişimde tembel yüklüyor, bean oluşturma anında değil.

**Olumsuz**
- Boot 4 ekosistemi henüz genç; bazı kütüphaneler geride kalabilir
- Jackson 3'e geçiş, ileride Jackson 2 bekleyen bir bağımlılık gelirse iki eşleyici
  taşımayı gerektirebilir

## Yeniden değerlendirme koşulları

- İhtiyaç duyulan bir kütüphanenin Boot 4 desteği yoksa ve ara katman yazmak maliyetliyse —
  o durumda Boot 3.5'e dönmek değerlendirilir
