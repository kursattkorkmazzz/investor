package com.investor.ontology.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Testlerde zamanı ileri sarmak için.
 *
 * <p>Bitemporal davranışın testi, {@code recorded_at}'ı kontrol edebilmeyi gerektirir:
 * "bir şeyi ne zaman öğrendik" sorusunun cevabı wall-clock'a bırakılamaz.
 */
public final class MutableClock extends Clock {

    private volatile Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public void setTo(Instant instant) {
        this.now = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now, newZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
