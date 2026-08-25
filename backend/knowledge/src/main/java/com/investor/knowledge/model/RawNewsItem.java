package com.investor.knowledge.model;

import java.time.Instant;

/**
 * Bir beslemeden çekilmiş ham haber.
 *
 * @param publishedAt yayın zamanı. Beslemede yoksa çekme zamanına düşer — ama bu bir
 *                    kayıptır: yayın zamanı bilinmeyen haber, backtest'te yanlış anda
 *                    görünür.
 */
public record RawNewsItem(
        String url,
        String title,
        String summary,
        String body,
        Instant publishedAt) {

    public RawNewsItem {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("haber url'i zorunlu");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("haber başlığı zorunlu");
        }
    }

    /** Benzerlik hesabında kullanılan metin: başlık + varsa özet. */
    public String similarityText() {
        return summary == null || summary.isBlank() ? title : title + " " + summary;
    }
}
