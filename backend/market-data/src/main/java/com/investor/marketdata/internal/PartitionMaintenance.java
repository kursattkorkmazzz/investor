package com.investor.marketdata.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Aylık partition'ları önden açar.
 *
 * <p>{@code pg_partman} RDS'te bulunmayabilir ve build'i doğrulanmamış bir eklentiye
 * bağlamak istemedik ({@code docs/adr/0003}). İş basit: önümüzdeki birkaç ayın
 * partition'ı hazır olsun ki ay dönümünde yazma patlamasın.
 *
 * <p>DEFAULT partition bilinçli olarak yok. Olsaydı aralık dışı bir yazma sessizce
 * oraya düşer ve sonradan doğru partition'a taşınması gerekirdi; hata vermesi daha iyi.
 */
class PartitionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenance.class);
    private static final List<String> PARENTS = List.of("ohlcv", "derivative_metric");

    private final JdbcClient jdbc;
    private final Clock clock;
    private final int monthsAhead;

    PartitionMaintenance(JdbcClient jdbc, Clock clock, int monthsAhead) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.monthsAhead = monthsAhead;
    }

    /** Geçmiş ay + içinde bulunulan ay + {@code monthsAhead} ay. */
    List<String> ensureUpcoming() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<String> created = new ArrayList<>();
        for (String parent : PARENTS) {
            for (int offset = -1; offset <= monthsAhead; offset++) {
                created.add(ensure(parent, today.plusMonths(offset)));
            }
        }
        log.debug("Partition bakımı tamam: {}", created.size());
        return List.copyOf(created);
    }

    /** Verilen aralığı kapsayan tüm partition'ları açar. Backfill yazmadan önce çağırır. */
    List<String> ensureRange(String parent, LocalDate fromInclusive, LocalDate toInclusive) {
        List<String> created = new ArrayList<>();
        LocalDate cursor = fromInclusive.withDayOfMonth(1);
        LocalDate end = toInclusive.withDayOfMonth(1);
        while (!cursor.isAfter(end)) {
            created.add(ensure(parent, cursor));
            cursor = cursor.plusMonths(1);
        }
        return List.copyOf(created);
    }

    private String ensure(String parent, LocalDate month) {
        return jdbc.sql("SELECT ensure_month_partition(:parent, CAST(:month AS date))")
                .param("parent", parent)
                .param("month", month.withDayOfMonth(1).toString())
                .query(String.class)
                .single();
    }
}
