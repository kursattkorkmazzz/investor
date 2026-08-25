package com.investor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulith;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Uygulama giriş noktası.
 *
 * <p>Modüler monolit: her doğrudan alt paket ({@code com.investor.ontology},
 * {@code com.investor.api}, ...) bir Spring Modulith modülüdür. Modüller arası
 * izinsiz erişim {@code ModularityTest} tarafından build zamanında yakalanır.
 */
@Modulith(systemName = "investor")
@SpringBootApplication
@EnableScheduling
public class InvestorApplication {

    public static void main(String[] args) {
        SpringApplication.run(InvestorApplication.class, args);
    }
}
