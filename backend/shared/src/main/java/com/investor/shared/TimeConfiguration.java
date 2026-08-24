package com.investor.shared;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sistemdeki tek zaman kaynağı.
 *
 * <p>Hiçbir yerde {@code Instant.now()} doğrudan çağrılmaz; her bileşen {@link Clock}
 * enjekte eder. Bitemporal davranışı ve backtest'i test edebilmenin tek yolu zamanı
 * kontrol edebilmek — bu kural mimarinin gereği, stil tercihi değil.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
