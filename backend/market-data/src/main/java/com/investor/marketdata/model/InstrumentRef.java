package com.investor.marketdata.model;

import java.util.UUID;

/**
 * Bir işlem enstrümanına referans.
 *
 * @param id       piyasa verisi tablolarındaki kimlik
 * @param objectId ontolojideki {@code Instrument} nesnesi — iki katman arasındaki tek bağ
 */
public record InstrumentRef(long id, UUID objectId, String exchange, String symbol) {

    public String qualifiedSymbol() {
        return exchange + ":" + symbol;
    }
}
