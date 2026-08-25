package com.investor.marketdata.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Verinin tazeliği.
 *
 * <p>Analiz ajanları buna bakar: bir kaynak eşiğin üstünde bayatsa ajan çekimser kalır.
 * Bayat veriyle taze veri gibi konuşmak, sistemin yapabileceği en sinsi hatadır —
 * hiçbir yerde hata olarak görünmez, sadece kararlar kötüleşir.
 */
public record Freshness(Instant asOf, Instant lastFinalOpenTime, Duration staleness, boolean stale) {

    public Optional<Instant> lastFinalOpen() {
        return Optional.ofNullable(lastFinalOpenTime);
    }

    /** Hiç veri yoksa: sonsuz bayat. */
    public static Freshness missing(Instant asOf) {
        return new Freshness(asOf, null, Duration.ofDays(36500), true);
    }
}
