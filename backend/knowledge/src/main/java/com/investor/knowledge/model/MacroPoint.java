package com.investor.knowledge.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Bir makro serinin tek gözlemi — belirli bir yayın sürümüyle (vintage).
 *
 * <p>Makro veriler revize edilir. Temmuz CPI'ı 15 Ağustos'ta 314.2 olarak yayınlanır,
 * 15 Eylül'de 314.5'e düzeltilir. İkisi de kendi döneminde "yayında olan gerçek"ti.
 *
 * @param period       gözlemin etiketlendiği dönem (FRED'de dönemin başlangıcı; değişmez)
 * @param vintageFrom  bu rakamın resmî rakam olmaya başladığı an → ontolojide valid_from
 * @param vintageTo    hangi ana kadar resmî kaldığı ({@code null} = hâlâ geçerli)
 * @param revision     ilk yayın mı, düzeltme mi
 */
public record MacroPoint(
        String seriesCode,
        LocalDate period,
        BigDecimal value,
        Instant vintageFrom,
        Instant vintageTo,
        boolean revision) {

    public MacroPoint {
        if (value == null) {
            throw new IllegalArgumentException("gözlem değeri zorunlu");
        }
        if (vintageTo != null && !vintageTo.isAfter(vintageFrom)) {
            throw new IllegalArgumentException("vintage aralığı geçersiz: %s → %s"
                    .formatted(vintageFrom, vintageTo));
        }
    }

    /** Ontolojideki gözlem nesnesinin doğal anahtarı. */
    public String externalId() {
        return "FRED:" + seriesCode + ":" + period;
    }
}
