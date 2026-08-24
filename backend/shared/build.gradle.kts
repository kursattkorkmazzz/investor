dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation("org.springframework.boot:spring-boot-starter")
    api("tools.jackson.core:jackson-databind")

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.assertj.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
