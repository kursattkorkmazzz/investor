package com.investor.analysis.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.investor.analysis.TriggerGate;
import com.investor.analysis.model.IndicatorSet;
import com.investor.analysis.model.Trigger;
import com.investor.analysis.model.TriggerContext;
import com.investor.analysis.model.TriggerDecision;

/**
 * Kural tabanlı tetikleyici kapısı.
 *
 * <h2>Geçiş (crossing) mantığı</h2>
 * Bu sınıfın en kritik davranışı: eşik <em>durumları</em> değil eşik <em>geçişleri</em>
 * tetikliyor. "RSI 28" durumunu tetikleyici saysaydık, RSI orada kaldığı sürece her tur
 * açılırdı — düşen bir piyasada bu, kapının tamamen devre dışı kalması demek. Oysa
 * "RSI aşırı satım bölgesine girdi" bir kez tetikler ve bilgi taşır.
 *
 * <p>Aynı mantık MACD kesişimi ve Bollinger kırılımı için de geçerli.
 *
 * <h2>Anlamlılık eşiği</h2>
 * Geçiş tespiti tek başına yetmedi. Ölçüm, MACD histogramının rastgele yürüyüşte sıfırın
 * etrafında sürekli salındığını ve 1500 mumda 121 "kesişim" ürettiğini gösterdi. Bunların
 * çoğu gerçek bir momentum dönüşü değil, sıfıra yakın gürültü.
 *
 * <p>Bu yüzden kesişimin <em>anlamlı</em> olması isteniyor: histogramın büyüklüğü fiyatın
 * {@link #MACD_MIN_MAGNITUDE_PCT}'i kadar olmalı. Yüzde olarak ifade edilmesi kritik —
 * mutlak bir eşik BTC ile bir altcoin arasında taşınamazdı.
 *
 * <p>Aynı sorun Bollinger'da da çıktı: 2σ bandının dışına çıkmak, tanımı gereği zamanın
 * ~%5'inde olan bir şey. Bandın <em>ucundan</em> değmek kırılım değil, o yüzden
 * {@link #BOLLINGER_MIN_EXCESS} kadar aşılması isteniyor.
 *
 * <h2>Neden anomali eşikleri geçiş değil</h2>
 * Hacim z-skoru ve fiyat şoku istisna: bunlar zaten <em>tek mumluk</em> olaylar, doğaları
 * gereği kalıcı değil. Geçiş aramak gereksiz karmaşıklık olurdu.
 *
 * <h2>Planlı gözden geçirme</h2>
 * Açık pozisyon varken hiçbir tetikleyici olmasa bile 4 saatte bir tur açılıyor. Sebep:
 * pozisyonu açan tez zamanla çürüyebilir ve bunun hiçbir gösterge eşiğine yansımaması
 * mümkün. Pozisyonsuzken bu tur yok — bakacak bir şey olmadığında bakmanın bedeli var.
 */
class DefaultTriggerGate implements TriggerGate {

    static final double RSI_OVERSOLD = 30;
    static final double RSI_OVERBOUGHT = 70;

    /** Hacim z-skoru bu değeri aşarsa anomali sayılıyor. */
    static final double VOLUME_Z_THRESHOLD = 3.0;

    /** Son mumun hareketi ATR'nin bu katını aşarsa şok sayılıyor. */
    static final double PRICE_SHOCK_ATR_MULTIPLE = 2.0;

    /**
     * MACD kesişiminin anlamlı sayılması için histogramın fiyata oranı, %.
     *
     * <p>Ölçümle seçildi: bu eşik olmadan rastgele yürüyüşte 1500 mumda 121 kesişim
     * çıkıyordu. Gerçek veriyle yeniden kalibre edilmeli.
     */
    static final double MACD_MIN_MAGNITUDE_PCT = 0.05;

    /**
     * Bollinger kırılımının sayılması için bandın ne kadar aşılması gerektiği (%B cinsinden).
     *
     * <p>0.05: fiyatın bant genişliğinin %%5'i kadar dışarı çıkması. Bandın ucundan değip
     * dönmek gürültüdür; ölçümde 1500 mumda 94 "kırılım" üretiyordu.
     */
    static final double BOLLINGER_MIN_EXCESS = 0.05;

    /** Haber bu önemin üstündeyse tur açılıyor. */
    static final double NEWS_MATERIALITY_THRESHOLD = 0.6;

    /** Açık pozisyon için planlı gözden geçirme aralığı. */
    static final Duration SCHEDULED_REVIEW_INTERVAL = Duration.ofHours(4);

    @Override
    public TriggerDecision evaluate(TriggerContext ctx) {
        List<Trigger> reasons = new ArrayList<>();

        rsiEntry(ctx, reasons);
        macdCross(ctx, reasons);
        bollingerBreakout(ctx, reasons);
        volumeAnomaly(ctx, reasons);
        priceShock(ctx, reasons);
        materialNews(ctx, reasons);
        regimeChange(ctx, reasons);
        scheduledReview(ctx, reasons);

        return reasons.isEmpty() ? TriggerDecision.closed() : TriggerDecision.opened(reasons);
    }

    /** RSI aşırı bölgeye <em>giriş</em>; bölgede kalmak tetiklemez. */
    private static void rsiEntry(TriggerContext ctx, List<Trigger> reasons) {
        Double now = value(ctx.indicators(), "rsi14");
        Double before = value(ctx.previousIndicators(), "rsi14");
        if (now == null || before == null) {
            return;
        }
        if (now <= RSI_OVERSOLD && before > RSI_OVERSOLD) {
            reasons.add(Trigger.of(Trigger.Type.RSI_EXTREME,
                    "RSI aşırı satım bölgesine girdi: %.1f → %.1f (eşik %.0f)"
                            .formatted(before, now, RSI_OVERSOLD),
                    RSI_OVERSOLD - now));
        } else if (now >= RSI_OVERBOUGHT && before < RSI_OVERBOUGHT) {
            reasons.add(Trigger.of(Trigger.Type.RSI_EXTREME,
                    "RSI aşırı alım bölgesine girdi: %.1f → %.1f (eşik %.0f)"
                            .formatted(before, now, RSI_OVERBOUGHT),
                    now - RSI_OVERBOUGHT));
        }
    }

    private static void macdCross(TriggerContext ctx, List<Trigger> reasons) {
        Double now = value(ctx.indicators(), "macdHistogram");
        Double before = value(ctx.previousIndicators(), "macdHistogram");
        if (now == null || before == null) {
            return;
        }
        // Sıfır tam olarak yakalanmıyor: 0'dan çıkışı da kesişim saymak, yatay
        // piyasada iki kez tetiklerdi (0'a giriş ve 0'dan çıkış).
        boolean crossedUp = before <= 0 && now > 0;
        boolean crossedDown = before >= 0 && now < 0;
        if (!crossedUp && !crossedDown) {
            return;
        }
        // Anlamlılık kontrolü: sıfırın etrafındaki gürültü kesişim sayılmıyor.
        Double close = value(ctx.indicators(), "close");
        if (close == null || close <= 0) {
            return;
        }
        double magnitudePct = Math.abs(now) / close * 100;
        if (magnitudePct < MACD_MIN_MAGNITUDE_PCT) {
            return;
        }
        reasons.add(Trigger.of(Trigger.Type.MACD_CROSS,
                "MACD histogramı işaret değiştirdi: %.6f → %.6f (%s, fiyatın %%%.3f'i)"
                        .formatted(before, now, crossedUp ? "yukarı" : "aşağı", magnitudePct),
                magnitudePct));
    }

    private static void bollingerBreakout(TriggerContext ctx, List<Trigger> reasons) {
        Double now = value(ctx.indicators(), "bbPercentB");
        Double before = value(ctx.previousIndicators(), "bbPercentB");
        if (now == null || before == null) {
            return;
        }
        double upperLimit = 1 + BOLLINGER_MIN_EXCESS;
        double lowerLimit = -BOLLINGER_MIN_EXCESS;
        if (now > upperLimit && before <= upperLimit) {
            reasons.add(Trigger.of(Trigger.Type.BOLLINGER_BREAKOUT,
                    "Fiyat üst Bollinger bandını belirgin şekilde aştı (%%B %.3f)".formatted(now),
                    now - 1));
        } else if (now < lowerLimit && before >= lowerLimit) {
            reasons.add(Trigger.of(Trigger.Type.BOLLINGER_BREAKOUT,
                    "Fiyat alt Bollinger bandının belirgin şekilde altına indi (%%B %.3f)"
                            .formatted(now), -now));
        }
    }

    private static void volumeAnomaly(TriggerContext ctx, List<Trigger> reasons) {
        if (ctx.stats() == null || !ctx.stats().has("volumeZScore")) {
            return;
        }
        double z = ctx.stats().get("volumeZScore").orElseThrow().doubleValue();
        if (Math.abs(z) >= VOLUME_Z_THRESHOLD) {
            reasons.add(Trigger.of(Trigger.Type.VOLUME_ANOMALY,
                    "Hacim z-skoru %.2f (eşik %.1f)".formatted(z, VOLUME_Z_THRESHOLD),
                    Math.abs(z)));
        }
    }

    /**
     * Son mumun hareketi ATR'nin katını aştı mı.
     *
     * <p>Mutlak yüzde eşiği yerine ATR katı: %3'lük bir hareket sakin bir varlıkta şok,
     * oynak bir altcoinde sıradan. Eşiğin kendisi varlığın oynaklığına göre ölçekleniyor.
     */
    private static void priceShock(TriggerContext ctx, List<Trigger> reasons) {
        if (ctx.stats() == null || ctx.indicators() == null) {
            return;
        }
        Double atrPct = value(ctx.indicators(), "atrPercent");
        if (atrPct == null || atrPct <= 0 || !ctx.stats().has("return1")) {
            return;
        }
        double move = Math.abs(ctx.stats().get("return1").orElseThrow().doubleValue());
        double threshold = atrPct * PRICE_SHOCK_ATR_MULTIPLE;
        if (move > threshold) {
            reasons.add(Trigger.of(Trigger.Type.PRICE_SHOCK,
                    "Son mum %%%.2f hareket etti; ATR %%%.2f'nin %.1f katı eşiği aşıldı"
                            .formatted(move, atrPct, PRICE_SHOCK_ATR_MULTIPLE),
                    move / threshold));
        }
    }

    private static void materialNews(TriggerContext ctx, List<Trigger> reasons) {
        if (ctx.maxNewsMateriality() > NEWS_MATERIALITY_THRESHOLD) {
            reasons.add(Trigger.of(Trigger.Type.MATERIAL_NEWS,
                    "Önem skoru %.2f olan yeni haber (eşik %.2f)"
                            .formatted(ctx.maxNewsMateriality(), NEWS_MATERIALITY_THRESHOLD),
                    ctx.maxNewsMateriality()));
        }
    }

    /**
     * Rejim değişimi.
     *
     * <p>Bilinmeyene <em>geçiş</em> tetiklemiyor: veri yetersizliğinden UNKNOWN'a düşmek
     * piyasa hakkında bir şey söylemez, yalnızca bizim hakkımızda. Bilinmeyenden bilinene
     * geçiş ise tetikliyor — o gerçekten yeni bilgi.
     */
    private static void regimeChange(TriggerContext ctx, List<Trigger> reasons) {
        if (ctx.regime() == null || ctx.previousRegime() == null) {
            return;
        }
        if (!ctx.regime().isKnown()) {
            return;
        }
        if (ctx.regime().differsFrom(ctx.previousRegime())) {
            reasons.add(Trigger.of(Trigger.Type.REGIME_CHANGE,
                    "Rejim değişti: %s → %s".formatted(
                            ctx.previousRegime().label(), ctx.regime().label()),
                    1));
        }
    }

    private static void scheduledReview(TriggerContext ctx, List<Trigger> reasons) {
        if (!ctx.hasOpenPosition()) {
            return;
        }
        if (ctx.lastRoundAt() == null) {
            reasons.add(Trigger.of(Trigger.Type.SCHEDULED_REVIEW,
                    "Açık pozisyon var ve henüz hiç tur koşmamış", 1));
            return;
        }
        Duration since = Duration.between(ctx.lastRoundAt(), ctx.asOf());
        if (since.compareTo(SCHEDULED_REVIEW_INTERVAL) >= 0) {
            reasons.add(Trigger.of(Trigger.Type.SCHEDULED_REVIEW,
                    "Açık pozisyon için planlı gözden geçirme (son tur %d saat önce)"
                            .formatted(since.toHours()),
                    since.toHours()));
        }
    }

    private static Double value(IndicatorSet set, String name) {
        if (set == null) {
            return null;
        }
        return set.get(name).map(v -> v.value().doubleValue()).orElse(null);
    }
}
