package com.investor.marketdata.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.investor.marketdata.model.Bar;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Mum yazma.
 *
 * <p>Yazma idempotenttir: aynı mumun tekrar çekilmesi çift kayıt üretmez, üzerine yazar.
 * Ingest hatları ağ hatasından sonra aynı aralığı yeniden çeker; bu normal ve beklenen.
 *
 * <p>{@code is_final} geri alınamaz: kapanmış bir mum tekrar açık hâle getirilemez.
 * Aksi hâlde geç gelen bir kaynak, hesaplanmış indikatörlerin altını sessizce oyardı.
 */
class BarWriter {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM-01")
            .withZone(ZoneOffset.UTC);

    private static final String UPSERT = """
            INSERT INTO ohlcv (instrument_id, timeframe, open_time, close_time,
                               open, high, low, close,
                               volume, quote_volume, trade_count, taker_buy_base, is_final)
            VALUES (:instrumentId, :timeframe, :openTime, :closeTime,
                    :open, :high, :low, :close,
                    :volume, :quoteVolume, :tradeCount, :takerBuyBase, :isFinal)
            ON CONFLICT (instrument_id, timeframe, open_time) DO UPDATE SET
                close_time     = EXCLUDED.close_time,
                open           = EXCLUDED.open,
                high           = EXCLUDED.high,
                low            = EXCLUDED.low,
                close          = EXCLUDED.close,
                volume         = EXCLUDED.volume,
                quote_volume   = EXCLUDED.quote_volume,
                trade_count    = EXCLUDED.trade_count,
                taker_buy_base = EXCLUDED.taker_buy_base,
                is_final       = ohlcv.is_final OR EXCLUDED.is_final,
                ingested_at    = now()
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcClient client;
    /** Bu JVM'de açıldığı doğrulanmış partition'lar; DB'ye gereksiz gidiş engellenir. */
    private final Set<String> ensuredMonths = ConcurrentHashMap.newKeySet();

    BarWriter(NamedParameterJdbcTemplate jdbc, JdbcClient client) {
        this.jdbc = jdbc;
        this.client = client;
    }

    int write(Collection<Bar> bars) {
        if (bars.isEmpty()) {
            return 0;
        }
        ensurePartitions(bars);

        SqlParameterSource[] batch = bars.stream().map(BarWriter::params).toArray(SqlParameterSource[]::new);
        int[] counts = jdbc.batchUpdate(UPSERT, batch);
        int written = 0;
        for (int count : counts) {
            written += Math.max(count, 0);
        }
        return written;
    }

    /**
     * DEFAULT partition bilinçli olarak yok; yazmadan önce ilgili ayların partition'ları
     * açılmalı. Aksi hâlde aralık dışı bir yazma hata verir — ki bu, sessizce yanlış yere
     * düşmekten iyidir ama önlenebilir bir hatadır.
     */
    private void ensurePartitions(Collection<Bar> bars) {
        Set<String> months = new LinkedHashSet<>();
        for (Bar bar : bars) {
            months.add(MONTH.format(bar.openTime()));
        }
        for (String month : months) {
            if (ensuredMonths.add(month)) {
                client.sql("SELECT ensure_month_partition('ohlcv', CAST(:month AS date))")
                        .param("month", month)
                        .query(String.class)
                        .optional();
            }
        }
    }

    /** Tek bir anın ayına ait partition'ı açar. Rollup, yazmadan önce bunu çağırır. */
    void ensureMonth(Instant instant) {
        String month = MONTH.format(instant);
        if (ensuredMonths.add(month)) {
            client.sql("SELECT ensure_month_partition('ohlcv', CAST(:month AS date))")
                    .param("month", month)
                    .query(String.class)
                    .optional();
        }
    }

    /** Testlerin veritabanını sıfırlaması sonrası önbelleği tazelemek için. */
    void resetPartitionCache() {
        ensuredMonths.clear();
    }

    private static SqlParameterSource params(Bar bar) {
        return new MapSqlParameterSource()
                .addValue("instrumentId", bar.instrumentId())
                .addValue("timeframe", bar.timeframe().code())
                .addValue("openTime", timestamp(bar.openTime()))
                .addValue("closeTime", timestamp(bar.closeTime()))
                .addValue("open", bar.open())
                .addValue("high", bar.high())
                .addValue("low", bar.low())
                .addValue("close", bar.close())
                .addValue("volume", bar.volume())
                .addValue("quoteVolume", bar.quoteVolume())
                .addValue("tradeCount", bar.tradeCount())
                .addValue("takerBuyBase", bar.takerBuyBase())
                .addValue("isFinal", bar.isFinal());
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    List<String> ensuredMonths() {
        return List.copyOf(ensuredMonths);
    }
}
