package com.investor.marketdata.internal;

import java.util.List;

import com.investor.marketdata.model.Bar;

import org.springframework.stereotype.Component;

/**
 * Testlerin modül içi bileşenlere erişim kapısı.
 *
 * <p>{@code BarWriter} bilinçli olarak paket-özel: üretim kodunda mum yazmanın tek yolu
 * ingest hattı olmalı. Testlerin doğrudan veri kurabilmesi için bu köprü, aynı pakette
 * ve yalnızca test kaynaklarında yaşıyor.
 */
@Component
public class MarketDataTestAccess {

    private final BarWriter writer;
    private final PartitionMaintenance partitions;

    MarketDataTestAccess(BarWriter writer, PartitionMaintenance partitions) {
        this.writer = writer;
        this.partitions = partitions;
    }

    public int write(List<Bar> bars) {
        return writer.write(bars);
    }

    public List<String> ensureUpcomingPartitions() {
        return partitions.ensureUpcoming();
    }

    public List<String> ensurePartitionRange(String parent, java.time.LocalDate from, java.time.LocalDate to) {
        return partitions.ensureRange(parent, from, to);
    }
}
