package com.investor.analysis.model;

import java.time.Instant;
import java.util.Objects;

import com.investor.marketdata.model.InstrumentRef;

/**
 * Tetikleyici kapısının baktığı her şey.
 *
 * <p><b>Neden önceki gösterge kümesi de var:</b> "RSI 28" ile "RSI aşırı satım bölgesine
 * <em>girdi</em>" farklı olaylar. Birincisi RSI orada kaldığı sürece her turda tetikler
 * ve kapıyı işlevsiz kılar; ikincisi bir kez tetikler. Geçiş tespiti için önceki mumun
 * değerleri gerekiyor.
 *
 * <p>Önceki kümeyi hesaplamak bir {@link com.investor.analysis.IndicatorService} çağrısı
 * daha demek — deterministik Java hesabı. LLM turu başına maliyetin yanında ihmal
 * edilebilir; doğru takas bu yönde.
 *
 * @param previousIndicators bir önceki mum kapanışındaki gösterge kümesi; ilk turda null
 * @param previousRegime     bir önceki turda kaydedilmiş rejim; ilk turda null
 * @param maxNewsMateriality son turdan bu yana gelen haberlerin en yüksek önem skoru
 * @param lastRoundAt        bu enstrüman için son tam turun zamanı; hiç koşmadıysa null
 */
public record TriggerContext(
        InstrumentRef instrument,
        Instant asOf,
        IndicatorSet indicators,
        IndicatorSet previousIndicators,
        PriceStats stats,
        Regime regime,
        Regime previousRegime,
        double maxNewsMateriality,
        boolean hasOpenPosition,
        Instant lastRoundAt) {

    public TriggerContext {
        Objects.requireNonNull(instrument, "enstrüman zorunlu");
        Objects.requireNonNull(asOf, "asOf zorunlu — kapı da geri testte koşuyor");
        if (maxNewsMateriality < 0 || maxNewsMateriality > 1) {
            throw new IllegalArgumentException("haber önemi 0–1 arasında olmalı: " + maxNewsMateriality);
        }
    }

    public static Builder at(InstrumentRef instrument, Instant asOf) {
        return new Builder(instrument, asOf);
    }

    /** Akıcı kurucu — çoğu alan isteğe bağlı ve varsayılanları güvenli tarafta. */
    public static final class Builder {
        private final InstrumentRef instrument;
        private final Instant asOf;
        private IndicatorSet indicators;
        private IndicatorSet previousIndicators;
        private PriceStats stats;
        private Regime regime;
        private Regime previousRegime;
        private double maxNewsMateriality;
        private boolean hasOpenPosition;
        private Instant lastRoundAt;

        private Builder(InstrumentRef instrument, Instant asOf) {
            this.instrument = instrument;
            this.asOf = asOf;
        }

        public Builder indicators(IndicatorSet current, IndicatorSet previous) {
            this.indicators = current;
            this.previousIndicators = previous;
            return this;
        }

        public Builder stats(PriceStats stats) {
            this.stats = stats;
            return this;
        }

        public Builder regime(Regime current, Regime previous) {
            this.regime = current;
            this.previousRegime = previous;
            return this;
        }

        public Builder news(double maxMateriality) {
            this.maxNewsMateriality = maxMateriality;
            return this;
        }

        public Builder openPosition(boolean open) {
            this.hasOpenPosition = open;
            return this;
        }

        public Builder lastRoundAt(Instant instant) {
            this.lastRoundAt = instant;
            return this;
        }

        public TriggerContext build() {
            return new TriggerContext(instrument, asOf, indicators, previousIndicators, stats,
                    regime, previousRegime, maxNewsMateriality, hasOpenPosition, lastRoundAt);
        }
    }
}
