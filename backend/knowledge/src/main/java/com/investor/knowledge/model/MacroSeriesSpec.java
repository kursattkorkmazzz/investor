package com.investor.knowledge.model;

/**
 * İzlenen makro seri.
 *
 * @param code      kaynak kodu (FRED: CPIAUCSL, FEDFUNDS, DGS10 …)
 * @param frequency yayın sıklığı — tazelik eşiği buna göre belirlenir
 */
public record MacroSeriesSpec(String code, String displayName, String unit, String frequency) {

    public static MacroSeriesSpec of(String code, String displayName) {
        return new MacroSeriesSpec(code, displayName, null, null);
    }

    public String externalId() {
        return "FRED:" + code;
    }
}
