package com.investor.api.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.investor.marketdata.InstrumentCatalog;
import com.investor.marketdata.MarketDataReader;
import com.investor.marketdata.model.Bar;
import com.investor.marketdata.model.Gap;
import com.investor.marketdata.model.InstrumentRef;
import com.investor.marketdata.model.Timeframe;
import com.investor.ontology.OntologyException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Piyasa verisi okuma uç noktaları.
 *
 * <p>Salt okunur ve yalnızca kapanmış mumları döner — {@link MarketDataReader}'ın
 * sözleşmesi HTTP'ye de aynen taşınıyor. {@code asOf} zorunlu değil ama verildiğinde
 * o ana kadar kapanmış mumlar döner; denetim ekranı bunu kullanır.
 */
@RestController
@RequestMapping("/api/market")
public class MarketDataController {

    private final MarketDataReader reader;
    private final InstrumentCatalog catalog;

    public MarketDataController(MarketDataReader reader, InstrumentCatalog catalog) {
        this.reader = reader;
        this.catalog = catalog;
    }

    public record InstrumentResponse(long id, String objectId, String exchange, String symbol) {
    }

    /** Ondalık değerler metin: JS'in double'ı 18 ondalıklı miktarları sessizce yuvarlar. */
    public record BarResponse(
            Instant openTime, Instant closeTime,
            String open, String high, String low, String close,
            String volume, String quoteVolume, int tradeCount, String takerBuyBase) {

        static BarResponse from(Bar bar) {
            return new BarResponse(bar.openTime(), bar.closeTime(),
                    plain(bar.open()), plain(bar.high()), plain(bar.low()), plain(bar.close()),
                    plain(bar.volume()), plain(bar.quoteVolume()), bar.tradeCount(),
                    plain(bar.takerBuyBase()));
        }

        private static String plain(BigDecimal value) {
            return value == null ? null : value.toPlainString();
        }
    }

    public record GapResponse(String timeframe, Instant from, Instant to, long missingBars) {
    }

    public record FreshnessResponse(Instant asOf, Instant lastFinalOpenTime,
                                    long stalenessSeconds, boolean stale) {
    }

    @GetMapping("/instruments")
    public List<InstrumentResponse> instruments() {
        return catalog.all().stream()
                .map(ref -> new InstrumentResponse(ref.id(),
                        ref.objectId() == null ? null : ref.objectId().toString(),
                        ref.exchange(), ref.symbol()))
                .toList();
    }

    @GetMapping("/bars")
    public List<BarResponse> bars(@RequestParam String instrument,
                                  @RequestParam String timeframe,
                                  @RequestParam(required = false) Instant from,
                                  @RequestParam(required = false) Instant to,
                                  @RequestParam(defaultValue = "200") int limit,
                                  @RequestParam(required = false) Instant asOf) {
        InstrumentRef ref = resolve(instrument);
        Timeframe tf = Timeframe.ofCode(timeframe);

        List<Bar> bars = (from != null && to != null)
                ? reader.finalBars(ref, tf, from, to)
                : reader.lastFinalBars(ref, tf, Math.min(limit, 1000),
                        asOf == null ? Instant.now() : asOf);

        return bars.stream().map(BarResponse::from).toList();
    }

    @GetMapping("/gaps")
    public List<GapResponse> gaps(@RequestParam String instrument,
                                  @RequestParam String timeframe,
                                  @RequestParam Instant from,
                                  @RequestParam Instant to) {
        List<Gap> gaps = reader.findGaps(resolve(instrument), Timeframe.ofCode(timeframe), from, to);
        return gaps.stream()
                .map(gap -> new GapResponse(gap.timeframe().code(),
                        gap.fromInclusive(), gap.toExclusive(), gap.missingBars()))
                .toList();
    }

    @GetMapping("/freshness")
    public FreshnessResponse freshness(@RequestParam String instrument,
                                       @RequestParam String timeframe,
                                       @RequestParam(required = false) Instant asOf) {
        var freshness = reader.freshness(resolve(instrument), Timeframe.ofCode(timeframe),
                asOf == null ? Instant.now() : asOf);
        return new FreshnessResponse(freshness.asOf(), freshness.lastFinalOpenTime(),
                freshness.staleness().toSeconds(), freshness.stale());
    }

    /** {@code BINANCE:BTCUSDT} biçimini çözer. */
    private InstrumentRef resolve(String qualified) {
        int separator = qualified.indexOf(':');
        if (separator < 0) {
            throw new IllegalArgumentException(
                    "enstrüman 'BORSA:SEMBOL' biçiminde olmalı: " + qualified);
        }
        String exchange = qualified.substring(0, separator);
        String symbol = qualified.substring(separator + 1);
        return catalog.find(exchange, symbol)
                .orElseThrow(() -> new OntologyException.NotFound("Enstrüman bulunamadı: " + qualified));
    }
}
