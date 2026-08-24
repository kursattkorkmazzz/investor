# ADR-0005 — Modüler monolit + transactional outbox, Kafka yok

**Durum:** Kabul edildi · 2026-08-24

## Bağlam

Sistem doğal olarak olay-güdümlü: veri geldi → analiz tetiklendi → karar üretildi →
emir gönderildi → sonuç ölçüldü → ders çıkarıldı. Decision engine ayrıca event
sourcing'e yakın bir yapıda.

Bu, Kafka'yı akla getiriyor. Ama sistem tek kullanıcılı, tek instance ve tek
geliştirici tarafından yazılıyor.

## Karar

Spring Modulith ile modüler monolit. Modüller arası iletişim domain event'leri
üzerinden; kalıcılık gereken yerde Spring Modulith'in event publication registry'si
(transactional outbox) kullanılır. Kafka yok.

Modül sınırları `ApplicationModuleTest` ile derleme/test zamanında zorlanır; izinsiz
bir `import` build'i kırar.

## Sonuçlar

**Olumlu**
- Tek deploy birimi, tek transaction sınırı, tek log akışı
- Outbox ile "veritabanına yaz + olay yayınla" atomik — Kafka'nın çözdüğü asıl
  problem zaten çözülmüş oluyor
- Yerel geliştirme tek `docker compose up`
- Modül sınırları test edildiği için, ileride servis ayırmak gerekirse kesme
  noktaları zaten net

**Olumsuz**
- Yatay ölçekleme sınırlı (şu an ihtiyaç yok; uygulama zaten durumsuz)
- Olay geçmişinin yeniden oynatılması Kafka'daki kadar doğal değil — ama decision
  engine kendi `decision_event` defterini append-only tuttuğu için karar tarafında
  replay zaten mümkün
- Bir modülün ağır işi tüm uygulamayı etkileyebilir (sanal iş parçacıkları ve
  ayrı executor havuzlarıyla hafifletiliyor)

## Değerlendirilen alternatifler

**Kafka/Redpanda day-1.** Tam event-driven, doğal replay ve audit. Tek kişilik ekipte
operasyon yükü ve geliştirme yavaşlaması, elde edilen faydadan büyük görüldü.

**Postgres LISTEN/NOTIFY.** Hafif, ama teslimat garantisi yok — dinleyici düşükken
yayınlanan bildirim kaybolur. Outbox bu garantiyi veriyor.

**Mikroservisler.** Bu ölçekte gereksiz; dağıtık sistem problemlerini bedava getirir.

## Yeniden değerlendirme koşulları

- Modülleri ayrı servislere bölmek gerekirse (ingest'in bağımsız ölçeklenmesi gibi)
- Outbox tablosu darboğaz olursa
- Birden fazla tüketicinin aynı olay akışını bağımsız hızlarda işlemesi gerekirse
- Bu durumda geçiş yolu açık: outbox tablosu Kafka'ya besleyen bir relay ile
  değiştirilir, modül sınırları zaten yerinde
