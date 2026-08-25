package com.investor.marketdata;

import java.util.List;
import java.util.Optional;

import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.InstrumentSpec;

/**
 * Enstrüman kayıt defteri.
 *
 * <p>Her enstrüman ontolojide bir {@code Instrument} nesnesi olarak da yaşar; burada
 * tutulan, piyasa verisi tablolarının ihtiyaç duyduğu kimlik ve emir kurallarıdır.
 */
public interface InstrumentCatalog {

    Optional<InstrumentRef> find(String exchange, String symbol);

    List<InstrumentRef> all();

    /**
     * Borsadan gelen tanımı kaydeder; varsa günceller.
     *
     * <p>{@code tickSize} ve {@code minNotional} zamanla değişir; güncel olmayan bir
     * değer, borsanın reddettiği emir demektir.
     */
    InstrumentRef register(InstrumentSpec spec);

    Optional<InstrumentSpec> spec(InstrumentRef instrument);
}
