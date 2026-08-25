package com.investor.marketdata.model;

import java.math.BigDecimal;

/**
 * Borsanın bildirdiği enstrüman tanımı.
 *
 * @param tickSize    fiyat adımı; emir fiyatı buna hizalanmazsa borsa reddeder
 * @param stepSize    miktar adımı; miktar her zaman <em>aşağı</em> yuvarlanır
 * @param minNotional asgari emir büyüklüğü (fiyat × miktar)
 */
public record InstrumentSpec(
        String exchange,
        String symbol,
        String baseAsset,
        String quoteAsset,
        InstrumentStatus status,
        BigDecimal tickSize,
        BigDecimal stepSize,
        BigDecimal minNotional) {

    public enum InstrumentStatus {
        TRADING,
        HALT,
        DELISTED,
        UNKNOWN
    }
}
