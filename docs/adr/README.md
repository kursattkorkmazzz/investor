# Mimari Karar Kayıtları (ADR)

Her dosya bir mimari kararı, gerekçesini, sonuçlarını ve hangi koşulda yeniden
değerlendirileceğini kaydeder. Karar değiştiğinde dosya silinmez — durumu
`Değiştirildi` yapılır ve yeni ADR'ye bağlanır.

| # | Karar | Durum |
|---|---|---|
| [0001](0001-ontoloji-bitemporal-eav.md) | Ontoloji: Postgres üzerinde bitemporal EAV | Kabul edildi |
| [0002](0002-sadece-java-runtime.md) | Runtime: sadece Java, Python sidecar yok | Kabul edildi |
| [0003](0003-rds-postgres-timescaledb-yok.md) | Veritabanı: AWS RDS PostgreSQL, TimescaleDB yok | Kabul edildi |
| [0004](0004-canli-spot-kucuk-sermaye.md) | Faz-1: küçük sermaye ile canlı Binance Spot | Kabul edildi |
| [0005](0005-modular-monolith-outbox.md) | Modüler monolit + transactional outbox, Kafka yok | Kabul edildi |
| [0006](0006-jooq-yerine-jdbcclient.md) | Veri erişimi: jOOQ yerine Spring JdbcClient | Kabul edildi |
| [0007](0007-spring-boot-4.md) | Spring Boot 4 (Jackson 3, bölünmüş autoconfiguration) | Kabul edildi |
