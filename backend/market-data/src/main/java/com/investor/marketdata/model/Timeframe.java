package com.investor.marketdata.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Mum zaman dilimi.
 *
 * <p>Hepsi epoch'a hizalı sabit süreler; bu yüzden {@link #floor(Instant)} basit tamsayı
 * bölmesiyle doğru çalışır. Haftalık ya da aylık bir dilim eklenirse bu varsayım kırılır —
 * takvim ayları sabit uzunlukta değildir ve o durumda hizalama takvim tabanlı olmalıdır.
 */
public enum Timeframe {

    M1("1m", Duration.ofMinutes(1)),
    M5("5m", Duration.ofMinutes(5)),
    M15("15m", Duration.ofMinutes(15)),
    H1("1h", Duration.ofHours(1)),
    H4("4h", Duration.ofHours(4)),
    D1("1d", Duration.ofDays(1));

    /** Tüm üst dilimler 1m'den türetilir; tek kaynak, tek doğruluk sorumluluğu. */
    public static final Timeframe BASE = M1;

    private final String code;
    private final Duration duration;

    Timeframe(String code, Duration duration) {
        this.code = code;
        this.duration = duration;
    }

    /** Veritabanında ve borsa API'lerinde kullanılan kod. */
    public String code() {
        return code;
    }

    public Duration duration() {
        return duration;
    }

    public static Timeframe ofCode(String code) {
        for (Timeframe tf : values()) {
            if (tf.code.equals(code)) {
                return tf;
            }
        }
        throw new IllegalArgumentException("Bilinmeyen zaman dilimi: " + code);
    }

    /** 1m'den türetilenler — yani {@link #BASE} dışındaki hepsi. */
    public static List<Timeframe> derived() {
        return List.of(M5, M15, H1, H4, D1);
    }

    /** Bu dilimden bir mum üretmek için gereken taban mum sayısı. */
    public int baseBarCount() {
        if (this == BASE) {
            return 1;
        }
        long ratio = duration.toSeconds() / BASE.duration.toSeconds();
        return Math.toIntExact(ratio);
    }

    /** Verilen anı içeren mumun açılış zamanı. */
    public Instant floor(Instant instant) {
        long step = duration.toSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(instant.getEpochSecond(), step) * step);
    }

    public Instant nextOpen(Instant openTime) {
        return openTime.plus(duration);
    }

    /**
     * Mumun kapanış zamanı — aralığın son anı, bir sonraki mumun açılışı değil.
     *
     * <p>Borsa API'leri (Binance dahil) kapanışı bu şekilde bildirir; aradaki 1 ms'lik
     * fark, "kapandı mı" kontrolünü yanlış yapmaya yeter.
     */
    public Instant closeTime(Instant openTime) {
        return nextOpen(openTime).minusMillis(1);
    }

    /**
     * Verilen ana göre en son <em>kapanmış</em> mumun açılış zamanı.
     *
     * <p>{@code floor(asOf)} ile başlayan mum {@code asOf} anında hâlâ açıktır — bir
     * önceki mum en son kapanandır. Sınır anında da aynı: {@code asOf == t} ise
     * {@code [t, t+d)} yeni açılmış, {@code [t-d, t)} yeni kapanmıştır.
     */
    public Instant lastClosedOpen(Instant asOf) {
        return floor(asOf).minus(duration);
    }

    /** Bu dilimin taban dilimden türetilip türetilmediği. */
    public Optional<Timeframe> rollupSource() {
        return this == BASE ? Optional.empty() : Optional.of(BASE);
    }
}
