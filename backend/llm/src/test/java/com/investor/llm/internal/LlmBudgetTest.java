package com.investor.llm.internal;

import java.math.BigDecimal;
import java.time.Instant;

import com.investor.llm.LlmException;
import com.investor.llm.LlmUsage;
import com.investor.ontology.support.MutableClock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bütçe tavanı testleri.
 *
 * <p>Bu bir güvenlik bileşeni: kaçak bir döngü gerçek para harcar. Tavanın gerçekten
 * durdurduğunu ve yeniden başlatmadan sonra unutmadığını doğruluyoruz.
 */
class LlmBudgetTest {

    private static final BigDecimal INPUT = new BigDecimal("1.00");
    private static final BigDecimal CACHED = new BigDecimal("0.50");
    private static final BigDecimal OUTPUT = new BigDecimal("4.00");

    @Test
    @DisplayName("Tavan aşılınca çağrı reddedilir")
    void refusesCallsOverLimit() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T09:00:00Z"));
        LlmBudget budget = new LlmBudget(new BigDecimal("1.00"), INPUT, CACHED, OUTPUT, clock);

        assertThatCode(() -> budget.checkAllowed("test")).doesNotThrowAnyException();

        // 1M girdi + 0 çıktı = 1.00 USD → tavana tam oturur.
        budget.record("test", new LlmUsage(1_000_000, 0, 0, 0));

        assertThatThrownBy(() -> budget.checkAllowed("test"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("bütçesi doldu")
                // Kalıcı hata: yeniden denemenin anlamı yok, para yine yok.
                .satisfies(e -> assertThat(((LlmException) e).retryable()).isFalse());
    }

    @Test
    @DisplayName("Önbellekli girdi ucuz fiyatlanır")
    void pricesCachedInputSeparately() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T09:00:00Z"));
        LlmBudget budget = new LlmBudget(new BigDecimal("100"), INPUT, CACHED, OUTPUT, clock);

        // 1M girdinin 800k'sı önbellekte: 200k * 1.00 + 800k * 0.50 = 0.60 USD
        budget.record("test", new LlmUsage(1_000_000, 800_000, 0, 0));

        assertThat(budget.totalSpend()).isEqualByComparingTo("0.60");
    }

    @Test
    @DisplayName("Ay değişince sayaç sıfırlanır")
    void resetsOnMonthChange() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-31T23:00:00Z"));
        LlmBudget budget = new LlmBudget(new BigDecimal("1.00"), INPUT, CACHED, OUTPUT, clock);
        budget.record("test", new LlmUsage(1_000_000, 0, 0, 0));
        assertThatThrownBy(() -> budget.checkAllowed("test")).isInstanceOf(LlmException.class);

        clock.setTo(Instant.parse("2026-09-01T00:30:00Z"));

        assertThatCode(() -> budget.checkAllowed("test")).doesNotThrowAnyException();
        assertThat(budget.totalSpend()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Geçmiş harcama yüklenince tavan hemen devreye girer")
    void seededSpendCountsAgainstLimit() {
        // Yeniden başlatma senaryosu: sayaç sıfırdan başlasaydı tavan hiçbir şey ifade etmezdi.
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T09:00:00Z"));
        LlmBudget budget = new LlmBudget(new BigDecimal("10"), INPUT, CACHED, OUTPUT, clock);

        budget.seed("news-analysis", new BigDecimal("9.99"));
        assertThatCode(() -> budget.checkAllowed("news-analysis")).doesNotThrowAnyException();

        budget.seed("news-analysis", new BigDecimal("0.02"));
        assertThatThrownBy(() -> budget.checkAllowed("news-analysis"))
                .isInstanceOf(LlmException.class);
    }

    @Test
    @DisplayName("Tavan tüm amaçların toplamına uygulanır")
    void limitAppliesAcrossPurposes() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T09:00:00Z"));
        LlmBudget budget = new LlmBudget(new BigDecimal("1.00"), INPUT, CACHED, OUTPUT, clock);

        budget.record("news-analysis", new LlmUsage(600_000, 0, 0, 0));
        budget.record("technical-analysis", new LlmUsage(500_000, 0, 0, 0));

        // Tek başına hiçbiri tavanı aşmıyor ama toplam aşıyor — sınır toplamda.
        assertThatThrownBy(() -> budget.checkAllowed("news-analysis"))
                .isInstanceOf(LlmException.class);
    }
}
