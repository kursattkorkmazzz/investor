package com.investor.knowledge;

import java.util.List;
import java.util.UUID;

import com.investor.knowledge.model.NewsClusterView;
import com.investor.knowledge.model.RawNewsItem;

/** Haber toplama akışı. */
public interface NewsIngest {

    /** Etkin tüm beslemeleri çeker. Bir beslemenin hatası diğerlerini durdurmaz. */
    IngestReport ingestAll();

    /** Tek bir haberi hatta sokar — testler ve elle ekleme için. */
    UUID ingestOne(UUID feedId, RawNewsItem item);

    /** Son {@code hours} saatteki kümeler, son yayına göre azalan sırada. */
    List<NewsClusterView> recentClusters(int hours, int limit);

    UUID registerFeed(String kind, String name, String url, double reliability);

    /**
     * @param newClusters      yeni konu sayısı
     * @param joinedExisting   mevcut bir konuya eklenen kopya sayısı
     * @param duplicatesSkipped daha önce görülmüş, hiç işlenmeyen kayıt sayısı
     */
    record IngestReport(int fetched, int newClusters, int joinedExisting,
                        int duplicatesSkipped, int failedFeeds) {
    }
}
