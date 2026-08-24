package com.investor.ontology.support;

import java.sql.Connection;
import java.sql.DriverManager;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Testler için PostgreSQL kaynağı.
 *
 * <p>Varsayılan Testcontainers'tır. Docker'ın olmadığı ortamlarda (bazı CI runner'ları,
 * kısıtlı geliştirme kutuları) {@code investor.test.db.url} sistem özelliği ile harici
 * bir PostgreSQL kullanılabilir.
 *
 * <p>Şema testleri gerçek PostgreSQL ister: {@code EXCLUDE} kısıtları, {@code tstzrange}
 * operatörleri, kısmi indeksler ve plpgsql fonksiyonları gömülü bir veritabanında yok —
 * onlarsız test edilen şey, üretimde koşan şey değildir.
 */
public final class PostgresResource {

    private static final String IMAGE = "postgres:16-alpine";

    private static volatile PostgresResource instance;

    private final String url;
    private final String username;
    private final String password;

    private PostgresResource(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    public static PostgresResource get() {
        PostgresResource local = instance;
        if (local == null) {
            synchronized (PostgresResource.class) {
                local = instance;
                if (local == null) {
                    local = start();
                    instance = local;
                }
            }
        }
        return local;
    }

    private static PostgresResource start() {
        String externalUrl = property("investor.test.db.url", "INVESTOR_TEST_DB_URL");
        if (externalUrl != null && !externalUrl.isBlank()) {
            String user = orDefault(property("investor.test.db.username", "INVESTOR_TEST_DB_USERNAME"), "postgres");
            String pass = orDefault(property("investor.test.db.password", "INVESTOR_TEST_DB_PASSWORD"), "postgres");
            resetSchema(externalUrl, user, pass);
            return new PostgresResource(externalUrl, user, pass);
        }

        if (!DockerClientFactory.instance().isDockerAvailable()) {
            throw new IllegalStateException("""
                    Testler gerçek bir PostgreSQL gerektiriyor ama Docker bulunamadı.
                    Ya Docker'ı çalıştırın ya da harici bir veritabanı verin:
                      ./gradlew test -Dinvestor.test.db.url=jdbc:postgresql://localhost:5432/investor_test \\
                                     -Dinvestor.test.db.username=... -Dinvestor.test.db.password=...
                    """);
        }

        @SuppressWarnings("resource") // JVM ömrü boyunca yaşar, ryuk temizler
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("investor")
                .withUsername("investor")
                .withPassword("investor");
        container.start();
        return new PostgresResource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /**
     * Harici veritabanının şemasını sıfırdan kurar.
     *
     * <p>Testcontainers her koşuda yepyeni bir veritabanı verir; harici veritabanı yolunda
     * aynı temiz sayfayı biz sağlıyoruz. Yayınlanmamış migration'lar geliştirme sırasında
     * değiştiği için, kalıntı bir şema Flyway checksum doğrulamasında patlar.
     *
     * <p><b>Dikkat:</b> {@code investor.test.db.url} yalnızca tek kullanımlık bir test
     * veritabanını göstermelidir — bu metot {@code public} şemayı olduğu gibi siler.
     */
    private static void resetSchema(String url, String user, String pass) {
        try (Connection connection = DriverManager.getConnection(url, user, pass);
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
        } catch (Exception e) {
            throw new IllegalStateException("Harici test veritabanı hazırlanamadı: " + url, e);
        }
    }

    private static String property(String systemProperty, String envVar) {
        String value = System.getProperty(systemProperty);
        return value != null ? value : System.getenv(envVar);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public String url() {
        return url;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }
}
