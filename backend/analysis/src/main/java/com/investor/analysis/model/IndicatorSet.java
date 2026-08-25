package com.investor.analysis.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.Timeframe;

/**
 * Bir enstrüman ve zaman dilimi için {@code asOf} anında hesaplanmış göstergeler.
 *
 * <p><b>Eksik gösterge, yanlış göstergeden iyidir.</b> Isınma süresi dolmamış bir
 * gösterge bu kümede <em>yer almaz</em>; adı {@link #unavailable()} listesine yazılır.
 * ta4j ısınmamış bir EMA için de bir sayı döner — o sayı yanlış değil, "henüz anlamlı
 * değil"dir, ki bu daha tehlikelidir: hata gibi görünmez, sadece sessizce yanlış yönlendirir.
 *
 * @param lastBarOpenTime hesabın dayandığı son <em>kapanmış</em> mumun açılış zamanı.
 *                        {@code asOf} ile arasındaki fark verinin ne kadar geriden
 *                        geldiğini gösterir.
 * @param unavailable     ısınma yetersizliği nedeniyle üretilemeyen göstergeler
 */
public record IndicatorSet(
        InstrumentRef instrument,
        Timeframe timeframe,
        Instant asOf,
        Instant lastBarOpenTime,
        int barsAvailable,
        Map<String, IndicatorValue> values,
        List<String> unavailable) {

    public IndicatorSet {
        values = values == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(values));
        unavailable = unavailable == null ? List.of() : List.copyOf(unavailable);
    }

    /** Hiç mum bulunamadığında dönen boş küme. */
    public static IndicatorSet empty(InstrumentRef instrument, Timeframe timeframe, Instant asOf) {
        return new IndicatorSet(instrument, timeframe, asOf, null, 0, Map.of(), List.of());
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public Optional<IndicatorValue> get(String name) {
        return Optional.ofNullable(values.get(name));
    }

    public Optional<BigDecimal> value(String name) {
        return get(name).map(IndicatorValue::value);
    }

    /**
     * Gösterge değeri ya da yoksa {@code fallback}.
     *
     * <p>Kural tabanlı bileşenler (rejim sınıflandırıcı, tetikleyici kapısı) için;
     * eksik göstergeyi sessizce sıfır saymamak adına çağıranın açıkça bir değer
     * belirtmesi gerekiyor.
     */
    public double doubleOr(String name, double fallback) {
        return get(name).map(IndicatorValue::doubleValue).orElse(fallback);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
