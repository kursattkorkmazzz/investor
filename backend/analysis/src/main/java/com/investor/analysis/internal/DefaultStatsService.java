package com.investor.analysis.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import com.investor.analysis.Descriptives;
import com.investor.analysis.StatsService;
import com.investor.analysis.model.PriceStats;
import com.investor.analysis.model.StatValue;
import com.investor.marketdata.MarketDataReader;
import com.investor.marketdata.model.Bar;
import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.Timeframe;

/**
 * Fiyat ve hacim istatistikleri.
 *
 * <p>Hesaplananlar ve neden:
 * <ul>
 *   <li><b>Getiriler</b> (1, 24, 168 mum): "ne kadar hareket etti" sorusunun cevabı.
 *       Log getiri kullanılıyor — toplanabilir ve simetrik.</li>
 *   <li><b>Gerçekleşen oynaklık</b>, yıllıklandırılmış: kripto 7/24 işlem gördüğü için
 *       yıllıklandırma çarpanı doğrudan zaman diliminden çıkıyor. Yıllıklandırılmış olması
 *       önemli — %2'lik saatlik sapma ile %2'lik günlük sapma aynı şey değil ve modele
 *       ham sapma vermek bu farkı gizler.</li>
 *   <li><b>Oynaklık persentili</b>: "şu an olağandan mı oynak" sorusu. Rejim
 *       sınıflandırıcısının ana girdisi.</li>
 *   <li><b>Hacim z-skoru</b>: tetikleyici kapısının anomali sinyali.</li>
 *   <li><b>Fiyat persentili</b>: fiyatın kendi geçmiş aralığındaki konumu.</li>
 * </ul>
 *
 * <p>Tüm pencereler {@code asOf} anında kapanmış mumlarla sınırlı; okuma tek yoldan,
 * {@link MarketDataReader#lastFinalBars} üzerinden yapılıyor.
 */
class DefaultStatsService implements StatsService {

    /** Persentil ve z-skor için tarihsel pencere. */
    static final int HISTORY_BARS = 720;

    /** Oynaklık ve hacim istatistiklerinin kısa penceresi. */
    static final int SHORT_WINDOW = 24;

    /** Getiri pencereleri (mum sayısı) ve okunabilir adları. */
    private static final int[] RETURN_WINDOWS = {1, 24, 168};

    /**
     * Rolling oynaklık serisi için gereken en az mum sayısı.
     *
     * <p>Persentil, tek bir gözlemle anlamsız; en az bu kadar tarihsel oynaklık gözlemi
     * yoksa oynaklık persentili hiç üretilmiyor.
     */
    static final int MIN_VOL_SAMPLES = 30;

    private static final int SCALE = 8;

    private final MarketDataReader reader;

    DefaultStatsService(MarketDataReader reader) {
        this.reader = reader;
    }

    @Override
    public PriceStats compute(InstrumentRef instrument, Timeframe timeframe, Instant asOf) {
        List<Bar> bars = reader.lastFinalBars(instrument, timeframe, HISTORY_BARS, asOf);
        if (bars.size() < 2) {
            return PriceStats.empty(instrument, timeframe, asOf);
        }

        double[] closes = closes(bars);
        double[] volumes = volumes(bars);
        double current = closes[closes.length - 1];
        Map<String, StatValue> values = new LinkedHashMap<>();

        for (int window : RETURN_WINDOWS) {
            if (closes.length > window) {
                double past = closes[closes.length - 1 - window];
                if (past > 0) {
                    double pct = (current / past - 1) * 100;
                    values.put("return" + window, new StatValue("return" + window, scale(pct),
                            window + 1,
                            "Son %d %s mumundaki fiyat değişimi, %%".formatted(window, timeframe.code())));
                }
            }
        }

        double[] returns = Descriptives.logReturns(closes);
        double annualization = Math.sqrt(barsPerYear(timeframe));

        if (returns.length >= SHORT_WINDOW) {
            double[] recent = tail(returns, SHORT_WINDOW);
            double vol = Descriptives.standardDeviation(recent) * annualization * 100;
            values.put("realizedVol", new StatValue("realizedVol", scale(vol), recent.length,
                    "Son %d %s mumunun log getirilerinin standart sapması, yıllıklandırılmış, %%"
                            .formatted(SHORT_WINDOW, timeframe.code())));

            // Oynaklığın kendi geçmişindeki yeri: "şu an olağandan mı oynak".
            double[] volSeries = rollingVolatility(returns, SHORT_WINDOW, annualization);
            if (volSeries.length >= MIN_VOL_SAMPLES) {
                OptionalDouble rank = Descriptives.percentileRank(volSeries, vol);
                rank.ifPresent(r -> values.put("volPercentile",
                        new StatValue("volPercentile", scale(r), volSeries.length,
                                "Şu anki oynaklığın son %d gözlemdeki persentil sırası (0–100)"
                                        .formatted(volSeries.length))));
            }
        }

        if (volumes.length > SHORT_WINDOW) {
            // Şimdiki hacim karşılaştırma penceresinin dışında tutuluyor: kendisini de
            // içeren bir ortalamaya göre z-skor, anomaliyi olduğundan küçük gösterir.
            double[] priorVolumes = window(volumes, volumes.length - 1 - SHORT_WINDOW,
                    volumes.length - 1);
            double currentVolume = volumes[volumes.length - 1];
            Descriptives.zScore(priorVolumes, currentVolume).ifPresent(z ->
                    values.put("volumeZScore", new StatValue("volumeZScore", scale(z),
                            priorVolumes.length,
                            "Son mumun hacminin önceki %d mumun ortalamasından kaç standart sapma "
                                    .formatted(SHORT_WINDOW) + "uzakta olduğu")));
        }

        Descriptives.percentileRank(closes, current).ifPresent(r ->
                values.put("pricePercentile", new StatValue("pricePercentile", scale(r),
                        closes.length,
                        "Kapanış fiyatının son %d %s mumundaki persentil sırası (0–100)"
                                .formatted(closes.length, timeframe.code()))));

        return new PriceStats(instrument, timeframe, asOf, bars.size(), values);
    }

    /**
     * Kayan pencereli oynaklık serisi — persentil hesabının gözlem kümesi.
     *
     * <p>Pencereler örtüşüyor, dolayısıyla gözlemler bağımsız değil. Bu, persentili
     * geçersiz kılmıyor ama güven aralığı çıkarmaya elverişli kılmıyor da; bu yüzden
     * yalnızca sıralama amaçlı kullanılıyor, olasılık ifadesi türetilmiyor.
     */
    private static double[] rollingVolatility(double[] returns, int window, double annualization) {
        if (returns.length < window) {
            return new double[0];
        }
        int count = returns.length - window + 1;
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            out[i] = Descriptives.standardDeviation(window(returns, i, i + window))
                    * annualization * 100;
        }
        return out;
    }

    /** Kripto 7/24 işlem görüyor; yıllıklandırma çarpanı doğrudan periyottan çıkıyor. */
    private static double barsPerYear(Timeframe timeframe) {
        Duration year = Duration.ofDays(365);
        return (double) year.toSeconds() / timeframe.duration().toSeconds();
    }

    private static double[] closes(List<Bar> bars) {
        double[] out = new double[bars.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = bars.get(i).close().doubleValue();
        }
        return out;
    }

    private static double[] volumes(List<Bar> bars) {
        double[] out = new double[bars.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = bars.get(i).volume().doubleValue();
        }
        return out;
    }

    private static double[] tail(double[] values, int count) {
        return window(values, values.length - count, values.length);
    }

    private static double[] window(double[] values, int fromInclusive, int toExclusive) {
        return java.util.Arrays.copyOfRange(values, Math.max(0, fromInclusive), toExclusive);
    }

    private static BigDecimal scale(double value) {
        if (!Double.isFinite(value)) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
