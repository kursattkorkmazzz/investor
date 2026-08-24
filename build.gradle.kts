plugins {
    java
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "com.investor"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing", "-Xlint:-serial"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        // Docker yoksa Testcontainers yerine harici Postgres kullanılabilsin (bkz. PostgresTestSupport).
        listOf("investor.test.db.url", "investor.test.db.username", "investor.test.db.password")
            .forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
    }
}
