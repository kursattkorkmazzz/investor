dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))

    api("org.springframework.modulith:spring-modulith-api")

    // LangChain4j yalnızca 'implementation': porta sızmaması build zamanında da zorlansın
    // (ADR-0008). Bir başka modül LangChain4j tipine erişmek isterse derleme kırılır.
    implementation(libs.langchain4j)
    implementation(libs.langchain4j.open.ai)

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    runtimeOnly(libs.postgresql)

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
