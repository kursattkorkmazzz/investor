dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))

    api(project(":backend:ontology-core"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation(libs.springdoc.webmvc)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.assertj.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
