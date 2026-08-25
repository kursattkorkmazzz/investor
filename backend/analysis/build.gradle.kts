dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))

    api(project(":backend:market-data"))
    api(project(":backend:knowledge"))
    api("org.springframework.modulith:spring-modulith-api")

    implementation(project(":backend:llm"))

    // ta4j 'implementation': kütüphane tipleri porta sızmasın diye build zamanında
    // zorluyoruz — LangChain4j'de olduğu gibi (ADR-0008 ile aynı gerekçe).
    implementation(libs.ta4j.core)

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("tools.jackson.core:jackson-databind")
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(testFixtures(project(":backend:ontology-core")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-flyway")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
