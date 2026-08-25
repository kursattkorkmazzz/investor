package com.investor.knowledge;

import java.util.List;

import com.investor.knowledge.model.RawNewsItem;

/** Bir haber beslemesinden ham kayıtları çeker. */
public interface NewsFeedSource {

    /**
     * @param feedUrl besleme adresi
     * @return yayın zamanına göre artan sırada haberler
     */
    List<RawNewsItem> fetch(String feedUrl);
}
