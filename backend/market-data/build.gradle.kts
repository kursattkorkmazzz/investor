plugins {
    `java-test-fixtures`
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))

    api(project(":backend:ontology-core"))
    api("org.springframework.modulith:spring-modulith-api")

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework:spring-web")
    implementation("tools.jackson.core:jackson-databind")
    runtimeOnly(libs.postgresql)

    testFixturesApi(platform(libs.spring.boot.bom))

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(testFixtures(project(":backend:ontology-core")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-flyway")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.assertj.core)
    testImplementation("org.wiremock:wiremock-standalone:${libs.versions.wiremock.get()}")
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
