package com.investor.analysis.internal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.investor.analysis.IndicatorService;
import com.investor.analysis.model.IndicatorSet;
import com.investor.analysis.model.IndicatorValue;
import com.investor.marketdata.MarketDataReader;
import com.investor.marketdata.model.Bar;
import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.Timeframe;

import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.volume.VWAPIndicator;
import org.ta4j.core.num.DecimalNumFactory;
import org.ta4j.core.num.Num;

/**
 * ta4j tabanlı gösterge hesabı.
 *
 * <p>ta4j bu sınıfın dışına çıkmıyor; tipleri porta sızmıyor. LangChain4j'de olduğu gibi
 * sınır Gradle'da {@code implementation} bağımlılığıyla zorlanıyor.
 *
 * <h2>Isınma (warm-up) meselesi</h2>
 * Bu sınıfın en önemli davranışı burada. ta4j, 14 mumluk bir seride RSI(14) istediğinizde
 * size bir sayı verir. O sayı <em>hata değil</em> — sadece anlamlı değil, çünkü Wilder
 * yumuşatması özyinelemeli ve ilk değerler seri başlangıcının etkisini taşıyor. Aynı şey
 * EMA ve ATR için de geçerli.
 *
 * <p>Bu tür bir yanlışlık en tehlikeli olanı: hiçbir yerde istisna fırlatmaz, log'a
 * düşmez, testte kırmızı yanmaz. Sadece kararı sessizce zehirler.
 *
 * <p>Bu yüzden özyinelemeli göstergeler için {@link #RECURSIVE_WARMUP} katı mum
 * isteniyor; yoksa gösterge üretilmiyor ve adı {@code unavailable} listesine yazılıyor.
 * Eksik gösterge, yanlış göstergeden iyidir.
 *
 * <h2>Neden BigDecimal aritmetiği</h2>
 * ta4j varsayılan olarak {@code double} tabanlı {@code DoubleNum} kullanır. Burada
 * {@code DecimalNumFactory} seçiliyor: aynı girdiden aynı çıktı, platformdan ve JIT'ten
 * bağımsız. Geri testin tekrarlanabilir olması buna bağlı — ondalık kayan nokta
 * birikimi, uzun serilerde son basamağı oynatır ve bir eşik karşılaştırmasını çevirebilir.
 */
class Ta4jIndicatorService implements IndicatorService {

    /**
     * Özyinelemeli göstergeler (EMA, RSI, ATR) için periyot katı.
     *
     * <p>Üç yerine dört: EMA'da seri başlangıcının ağırlığı {@code (1-α)^n} ile azalır;
     * α = 2/(n+1) için dört periyot sonunda kalan etki ~%1'in altına iner. Üç periyotta
     * ~%5 civarı ve bu, eşik karşılaştırmalarını çevirmeye yetiyor.
     */
    static final int RECURSIVE_WARMUP = 4;

    /** Hesaplanacak en uzun pencere; okunacak mum sayısını bu belirliyor. */
    private static final int EMA_LONG = 200;
    private static final int EMA_MID = 50;
    private static final int EMA_SHORT = 20;
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;
    private static final int BB_PERIOD = 20;
    private static final int MACD_SHORT = 12;
    private static final int MACD_LONG = 26;
    private static final int MACD_SIGNAL = 9;
    private static final int VWAP_PERIOD = 20;

    /** İstenecek mum sayısı: en uzun pencere × ısınma katı, makul bir tavanla. */
    static final int BARS_TO_LOAD = Math.min(EMA_LONG * RECURSIVE_WARMUP, 1_000);

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final int SCALE = 10;

    private final MarketDataReader reader;

    Ta4jIndicatorService(MarketDataReader reader) {
        this.reader = reader;
    }

    @Override
    public IndicatorSet compute(InstrumentRef instrument, Timeframe timeframe, Instant asOf) {
        List<Bar> bars = reader.lastFinalBars(instrument, timeframe, BARS_TO_LOAD, asOf);
        if (bars.isEmpty()) {
            return IndicatorSet.empty(instrument, timeframe, asOf);
        }

        BarSeries series = toSeries(instrument, timeframe, bars);
        int last = series.getEndIndex();
        int count = bars.size();

        Map<String, IndicatorValue> values = new LinkedHashMap<>();
        List<String> unavailable = new ArrayList<>();
        Collector out = new Collector(values, unavailable, count, last, timeframe);

        ClosePriceIndicator close = new ClosePriceIndicator(series);

        out.put("close", close, 1, "Son kapanmış mumun kapanış fiyatı");

        out.recursive("rsi14", new RSIIndicator(close, RSI_PERIOD), RSI_PERIOD,
                "RSI(%d), Wilder yumuşatması, kapanış fiyatı".formatted(RSI_PERIOD));

        out.recursive("ema20", new EMAIndicator(close, EMA_SHORT), EMA_SHORT,
                "EMA(%d), kapanış fiyatı".formatted(EMA_SHORT));
        out.recursive("ema50", new EMAIndicator(close, EMA_MID), EMA_MID,
                "EMA(%d), kapanış fiyatı".formatted(EMA_MID));
        out.recursive("ema200", new EMAIndicator(close, EMA_LONG), EMA_LONG,
                "EMA(%d), kapanış fiyatı".formatted(EMA_LONG));

        MACDIndicator macd = new MACDIndicator(close, MACD_SHORT, MACD_LONG);
        EMAIndicator macdSignal = macd.getSignalLine(MACD_SIGNAL);
        out.recursive("macd", macd, MACD_LONG,
                "MACD(%d,%d) = EMA%d − EMA%d".formatted(MACD_SHORT, MACD_LONG, MACD_SHORT, MACD_LONG));
        out.recursive("macdSignal", macdSignal, MACD_LONG + MACD_SIGNAL,
                "MACD sinyal hattı: EMA(%d) of MACD".formatted(MACD_SIGNAL));
        // Histogram türetilmiş: ikisi de varsa hesaplanır, biri yoksa hiç üretilmez.
        if (values.containsKey("macd") && values.containsKey("macdSignal")) {
            BigDecimal hist = values.get("macd").value().subtract(values.get("macdSignal").value());
            values.put("macdHistogram", new IndicatorValue("macdHistogram", scale(hist),
                    MACD_LONG + MACD_SIGNAL, count, "MACD − sinyal hattı"));
        } else {
            unavailable.add("macdHistogram");
        }

        ATRIndicator atr = new ATRIndicator(series, ATR_PERIOD);
        out.recursive("atr14", atr, ATR_PERIOD,
                "ATR(%d), Wilder yumuşatması".formatted(ATR_PERIOD));
        // ATR'nin fiyata oranı: varlıklar arası karşılaştırılabilir tek oynaklık ölçüsü.
        // Çıplak ATR, BTC ile bir altcoin arasında kıyaslanamaz.
        if (values.containsKey("atr14")) {
            BigDecimal closeValue = values.get("close").value();
            if (closeValue.signum() != 0) {
                BigDecimal pct = values.get("atr14").value()
                        .divide(closeValue, MC).multiply(BigDecimal.valueOf(100), MC);
                values.put("atrPercent", new IndicatorValue("atrPercent", scale(pct), ATR_PERIOD,
                        count, "ATR(%d) / kapanış × 100 — oynaklığın fiyata oranı, %%"
                        .formatted(ATR_PERIOD)));
            }
        } else {
            unavailable.add("atrPercent");
        }

        // Bollinger SMA tabanlı: özyinelemeli değil, tam periyot yeterli.
        SMAIndicator sma = new SMAIndicator(close, BB_PERIOD);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma);
        StandardDeviationIndicator sd = new StandardDeviationIndicator(close, BB_PERIOD);
        out.put("bbMiddle", bbMiddle, BB_PERIOD, "Bollinger orta bant: SMA(%d)".formatted(BB_PERIOD));
        out.put("bbUpper", new BollingerBandsUpperIndicator(bbMiddle, sd), BB_PERIOD,
                "Bollinger üst bant: SMA(%d) + 2σ".formatted(BB_PERIOD));
        out.put("bbLower", new BollingerBandsLowerIndicator(bbMiddle, sd), BB_PERIOD,
                "Bollinger alt bant: SMA(%d) − 2σ".formatted(BB_PERIOD));
        if (values.containsKey("bbUpper") && values.containsKey("bbLower")
                && values.containsKey("bbMiddle")) {
            BigDecimal upper = values.get("bbUpper").value();
            BigDecimal lower = values.get("bbLower").value();
            BigDecimal middle = values.get("bbMiddle").value();
            BigDecimal width = upper.subtract(lower);
            if (middle.signum() != 0) {
                values.put("bbWidth", new IndicatorValue("bbWidth",
                        scale(width.divide(middle, MC).multiply(BigDecimal.valueOf(100), MC)),
                        BB_PERIOD, count,
                        "Bollinger bant genişliği: (üst − alt) / orta × 100, %"));
            }
            // %B: fiyatın bantlar içindeki konumu. 0 = alt bant, 1 = üst bant.
            if (width.signum() != 0) {
                BigDecimal percentB = values.get("close").value().subtract(lower)
                        .divide(width, MC);
                values.put("bbPercentB", new IndicatorValue("bbPercentB", scale(percentB),
                        BB_PERIOD, count,
                        "Bollinger %B: (kapanış − alt) / (üst − alt); 0 = alt bant, 1 = üst bant"));
            }
        }

        out.put("volume", new VolumeIndicator(series), 1, "Son mumun hacmi");
        out.put("vwap20", new VWAPIndicator(series, VWAP_PERIOD), VWAP_PERIOD,
                "VWAP(%d): son %d mumun hacim ağırlıklı ortalama fiyatı"
                        .formatted(VWAP_PERIOD, VWAP_PERIOD));

        return new IndicatorSet(instrument, timeframe, asOf,
                bars.get(bars.size() - 1).openTime(), count, values, unavailable);
    }

    /**
     * Bizim {@link Bar}'ımızı ta4j serisine çevirir.
     *
     * <p>{@code endTime} olarak mumun kapanış zamanı veriliyor: ta4j bir mumu bittiği anla
     * kimliklendiriyor ve açılış zamanı verilseydi tüm seri bir periyot geriye kayardı.
     */
    private static BarSeries toSeries(InstrumentRef instrument, Timeframe timeframe,
                                      List<Bar> bars) {
        BarSeries series = new BaseBarSeriesBuilder()
                .withName(instrument.qualifiedSymbol() + ":" + timeframe.code())
                .withNumFactory(DecimalNumFactory.getInstance())
                .build();
        for (Bar bar : bars) {
            series.barBuilder()
                    .timePeriod(timeframe.duration())
                    .endTime(bar.closeTime())
                    .openPrice(bar.open())
                    .highPrice(bar.high())
                    .lowPrice(bar.low())
                    .closePrice(bar.close())
                    .volume(bar.volume())
                    .add();
        }
        return series;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    /** Gösterge toplayıcı: ısınma kontrolünü tek yerde tutuyor. */
    private record Collector(
            Map<String, IndicatorValue> values,
            List<String> unavailable,
            int barsAvailable,
            int lastIndex,
            Timeframe timeframe) {

        /** Özyinelemeli olmayan gösterge: tam periyot kadar mum yeterli. */
        void put(String name, Indicator<Num> indicator, int period, String method) {
            record(name, indicator, period, period, method);
        }

        /** Özyinelemeli gösterge: periyodun {@link #RECURSIVE_WARMUP} katı gerekiyor. */
        void recursive(String name, Indicator<Num> indicator, int period, String method) {
            record(name, indicator, period, period * RECURSIVE_WARMUP, method);
        }

        private void record(String name, Indicator<Num> indicator, int period, int required,
                            String method) {
            if (barsAvailable < required) {
                unavailable.add(name);
                return;
            }
            Num num = indicator.getValue(lastIndex);
            if (num == null || num.isNaN()) {
                unavailable.add(name);
                return;
            }
            values.put(name, new IndicatorValue(name, scale(num.bigDecimalValue()), period,
                    barsAvailable, method + " · " + timeframe.code() + " mumları"));
        }
    }
}
