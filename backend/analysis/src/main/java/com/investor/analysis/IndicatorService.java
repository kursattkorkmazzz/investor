package com.investor.analysis;

import java.time.Instant;

import com.investor.analysis.model.IndicatorSet;
import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.Timeframe;

/**
 * Teknik gösterge hesabı — tamamen deterministik.
 *
 * <p>Aynı {@code asOf} ile iki kez çağrıldığında aynı sonucu verir. Bu, geri testin
 * anlamlı olmasının ön koşulu: gösterge hesabı çağrı anına bağlı olsaydı, aynı geçmiş
 * gün iki kez oynatıldığında iki farklı sonuç çıkardı.
 *
 * <p><b>{@code asOf} zorunlu.</b> "Son 200 mumu al" diyen bir imza geri testte sessizce
 * geleceğe uzanır. Sınır isteğe bağlı olsaydı unutulurdu.
 */
public interface IndicatorService {

    /**
     * {@code asOf} anında <em>kapanmış</em> mumlardan hesaplanan göstergeler.
     *
     * <p>{@code asOf} anında hâlâ açık olan mum hesaba girmez: kapanmamış mumdan
     * hesaplanan gösterge mum kapanınca değişir ve geri testte bu "geleceği görmek"tir.
     */
    IndicatorSet compute(InstrumentRef instrument, Timeframe timeframe, Instant asOf);
}
