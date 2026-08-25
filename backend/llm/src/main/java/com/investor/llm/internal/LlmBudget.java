package com.investor.llm.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.investor.llm.LlmException;
import com.investor.llm.LlmUsage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aylık harcama tavanı.
 *
 * <p>Bu bir güvenlik bileşeni, muhasebe kolaylığı değil. Kaçak bir döngü — bir yeniden deneme
 * sarmalı, bir zamanlayıcı hatası, bir ajanın kendini tetiklemesi — gerçek para harcar ve
 * fark edildiğinde harcanmış olur. Tavan aşıldığında çağrılar reddedilir; sistem LLM'siz
 * çalışmaya devam eder (kural tabanlı yedekler yerinde) ama körü körüne harcamaz.
 *
 * <p>Sayaç süreç içinde tutuluyor ve açılışta veritabanından geri yükleniyor. Tek örnekli
 * dağıtımda bu yeterli; çok örnekli dağıtıma geçildiğinde sayacın paylaşılması gerekecek
 * (bkz. {@code docs/05-analiz-ajanlari.md} maliyet bölümü).
 */
class LlmBudget {

    private static final Logger log = LoggerFactory.getLogger(LlmBudget.class);

    private final BigDecimal monthlyLimit;
    private final BigDecimal inputPerMillion;
    private final BigDecimal cachedPerMillion;
    private final BigDecimal outputPerMillion;
    private final Clock clock;
    private final Map<String, BigDecimal> spendByPurpose = new ConcurrentHashMap<>();
    private final AtomicReference<YearMonth> currentMonth = new AtomicReference<>();

    LlmBudget(BigDecimal monthlyLimit, BigDecimal inputPerMillion, BigDecimal cachedPerMillion,
              BigDecimal outputPerMillion, Clock clock) {
        this.monthlyLimit = monthlyLimit;
        this.inputPerMillion = inputPerMillion;
        this.cachedPerMillion = cachedPerMillion;
        this.outputPerMillion = outputPerMillion;
        this.clock = clock;
        this.currentMonth.set(month());
    }

    /**
     * Açılışta ya da yeniden başlatmada geçmiş harcamayı yükler — yoksa her yeniden başlatma
     * tavanı sıfırlar ve tavan anlamsızlaşır.
     */
    void seed(String purpose, BigDecimal alreadySpent) {
        rollOverIfNeeded();
        spendByPurpose.merge(purpose, alreadySpent, BigDecimal::add);
    }

    void checkAllowed(String purpose) {
        rollOverIfNeeded();
        BigDecimal spent = totalSpend();
        if (spent.compareTo(monthlyLimit) >= 0) {
            throw new LlmException(
                    "aylık LLM bütçesi doldu (%s / %s USD); çağrı reddedildi: %s"
                            .formatted(spent.setScale(2, java.math.RoundingMode.HALF_UP),
                                    monthlyLimit, purpose),
                    false);
        }
    }

    void record(String purpose, LlmUsage usage) {
        rollOverIfNeeded();
        BigDecimal cost = usage.cost(inputPerMillion, cachedPerMillion, outputPerMillion);
        BigDecimal total = spendByPurpose.merge(purpose, cost, BigDecimal::add);
        log.debug("LLM harcaması: purpose={} çağrı={} USD, ay toplamı={} USD", purpose, cost, total);
    }

    BigDecimal totalSpend() {
        return spendByPurpose.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    Map<String, BigDecimal> spendByPurpose() {
        return Map.copyOf(spendByPurpose);
    }

    BigDecimal monthlyLimit() {
        return monthlyLimit;
    }

    private void rollOverIfNeeded() {
        YearMonth now = month();
        YearMonth previous = currentMonth.getAndSet(now);
        if (!now.equals(previous)) {
            log.info("LLM bütçe ayı değişti ({} → {}); sayaç sıfırlandı, önceki toplam {} USD",
                    previous, now, totalSpend());
            spendByPurpose.clear();
        }
    }

    private YearMonth month() {
        return YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
    }
}
