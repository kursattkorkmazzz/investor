package com.investor.knowledge;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.investor.knowledge.model.MacroPoint;
import com.investor.knowledge.model.MacroSeriesSpec;

/**
 * Makro veri kaynağı.
 *
 * <p>Kaynak, revizyon geçmişini (vintage) döndürebilmelidir. Yalnızca güncel değeri veren
 * bir kaynak, "o gün hangi rakamı görüyorduk" sorusunu cevaplanamaz hâle getirir ve
 * backtest'i sistematik olarak iyimser gösterir.
 */
public interface MacroSource {

    String sourceName();

    Optional<MacroSeriesSpec> describe(String seriesCode);

    /**
     * Serinin gözlemlerini tüm yayın sürümleriyle döner.
     *
     * @param since bu tarihten sonraki dönemler; {@code null} ise seri başından
     */
    List<MacroPoint> observations(String seriesCode, LocalDate since);
}
