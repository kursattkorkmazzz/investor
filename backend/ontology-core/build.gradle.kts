plugins {
    `java-test-fixtures`
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))

    api(project(":backend:shared"))
    api("org.springframework.modulith:spring-modulith-api")

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("tools.jackson.core:jackson-databind")
    runtimeOnly(libs.postgresql)

    // testFixtures kendi çözümleme kapsamı; BOM'ları ayrıca almak zorunda.
    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi(platform(libs.spring.modulith.bom))
    testFixturesApi(platform(libs.testcontainers.bom))
    testFixturesApi("org.testcontainers:postgresql")
    testFixturesRuntimeOnly(libs.postgresql)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.boot:spring-boot-flyway")
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
