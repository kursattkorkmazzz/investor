# ADR-0006 — Veri erişimi: jOOQ yerine Spring JdbcClient

**Durum:** Kabul edildi · 2026-08-24 · [ADR-0001](0001-ontoloji-bitemporal-eav.md)'i tamamlar

## Bağlam

[09-tech-stack.md](../09-tech-stack.md) başlangıçta jOOQ öngörüyordu: ontoloji sorguları
elle SQL gerektiriyor (bitemporal aralıklar, `EXCLUDE` kısıtları, recursive CTE, kısmi
indeksler) ve jOOQ bunu tip güvenli hâle getiriyor — kolon adı değişince derleme kırılıyor.

Gerçekleme sırasında jOOQ'nun kod üretim adımının iki yoldan birini zorunlu kıldığı görüldü:

1. **Canlı veritabanına karşı üretim** — build'in çalışması için Docker ya da erişilebilir
   bir PostgreSQL gerekiyor. Bu, `./gradlew build`'i ağ ve konteyner altyapısına bağımlı kılar.
2. **DDL dosyalarından üretim** (`DDLDatabase`) — şemamız `EXCLUDE USING gist`,
   `tstzrange` ifadeleri, kısmi indeksler ve plpgsql fonksiyonları içeriyor. jOOQ'nun DDL
   ayrıştırıcısının bunları eksiksiz işleyeceği garanti değil; işlemezse kod üretimi
   şemanın bir kısmını sessizce kaçırır.

## Karar

Spring Framework'ün `JdbcClient`'ı ile elle yazılmış SQL. jOOQ yok, kod üretimi yok.

Kaybedilen derleme zamanı tip güvenliğini testler telafi ediyor: ontoloji testleri gerçek
PostgreSQL'e karşı koşuyor, dolayısıyla yanlış bir kolon adı derlemede değil ama yine de
build sırasında yakalanıyor.

## Sonuçlar

**Olumlu**
- Build'in dış bağımlılığı yok; `./gradlew build` her yerde çalışıyor
- SQL olduğu gibi okunuyor: `EXCLUDE`, `tstzrange`, recursive CTE hiçbir soyutlamadan
  geçmiyor. jOOQ ile bunların çoğu zaten `SQL.sql(...)` kaçışıyla yazılacaktı.
- Bir bağımlılık ve bir build adımı eksik

**Olumsuz**
- Kolon adı hatası derleme zamanında değil test zamanında yakalanıyor
- Dinamik sorgu derleyicisi elle yazıldı (`QueryCompiler`); jOOQ'nun DSL'i bu iş için
  gerçekten iyi. Güvenlik telafisi açık: alan adları `property_type`'a karşı çözülüyor,
  operatörler kapalı bir enum'dan geliyor, tüm değerler parametre olarak bağlanıyor —
  kullanıcı girdisinden türeyen hiçbir metin SQL'e birleştirilmiyor.

## Yeniden değerlendirme koşulları

- Elle yazılmış SQL'de kolon/tip hataları testlerde tekrar tekrar yakalanmaya başlarsa
- Şema büyüyüp sorgu sayısı elle yönetilemez hâle gelirse — o noktada Testcontainers
  tabanlı jOOQ kod üretimi, build'e eklenen Docker bağımlılığını hak eder
