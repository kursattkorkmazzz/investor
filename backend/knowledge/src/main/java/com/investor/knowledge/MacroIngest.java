package com.investor.knowledge;

import java.time.LocalDate;

/** Makro seri toplama akışı. */
public interface MacroIngest {

    /**
     * Seriyi ontolojiye yazar.
     *
     * <p>Her yayın sürümü ayrı bir geçerlilik aralığı olarak kaydedilir; revizyon eskisini
     * ezmez, kapatır.
     *
     * @return yazılan gözlem sayısı
     */
    int syncSeries(String seriesCode, LocalDate since);

    /** Yapılandırmadaki tüm serileri günceller. */
    int syncAll();
}
