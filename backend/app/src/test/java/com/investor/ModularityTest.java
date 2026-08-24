package com.investor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Modül sınırlarını build zamanında doğrular.
 *
 * <p>Modüler monolitin tek riski sınırların zamanla erimesidir: bugün "sadece şu tek
 * sınıfı çağırayım" diye atılan adım, altı ay sonra ayrıştırılamayan bir yumak üretir.
 * Bu test, izinsiz bir {@code import} eklendiği anda build'i kırar.
 */
@DisplayName("Modül sınırları")
class ModularityTest {

    static final ApplicationModules modules = ApplicationModules.of(InvestorApplication.class);

    @Test
    @DisplayName("modüller arası izinsiz erişim yok")
    void verifiesModuleStructure() {
        modules.verify();
    }

    @Test
    @DisplayName("modül dokümanı üretilir")
    void writesDocumentation() {
        new Documenter(modules).writeDocumentation();
    }
}
