package com.investor.knowledge.internal;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.investor.knowledge.NewsExtractor;
import com.investor.knowledge.model.EventType;
import com.investor.knowledge.model.NewsAnalysis;
import com.investor.knowledge.model.RawNewsItem;
import com.investor.llm.LlmCall;
import com.investor.llm.LlmClient;
import com.investor.llm.LlmException;
import com.investor.llm.LlmResult;
import com.investor.llm.OutputSchema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Haber çıkarımını bir dil modeline yaptırır.
 *
 * <p><b>Neden LLM:</b> kural tabanlı çıkarıcı anahtar kelime sayıyor. "SEC, ETF başvurusunu
 * <em>reddetmedi</em>" ile "SEC, ETF başvurusunu reddetti" onun için aynı; olumsuzlama,
 * bağlam ve ima anahtar kelimeyle yakalanmıyor. Bu, haberin en çok değer taşıyan kısmı.
 *
 * <p><b>Yedeğe düşme:</b> model erişilemezse ya da cevap doğrulamayı geçemezse kural tabanlı
 * çıkarıcı devreye giriyor ve sonuç <em>onun</em> kimliğiyle etiketleniyor. Hattın durmaması
 * için değil sadece — çıkarım kalitesinin düşmesi, haberin hiç kaydedilmemesinden iyidir.
 * Ama kimliğin doğru olması şart: yoksa kalibrasyon iki çıkarıcının sonuçlarını karıştırır.
 *
 * <p><b>Bilinen sınır:</b> yalnızca başlık ve özet gönderiliyor, gövde değil. Gövde token
 * maliyetini birkaç kat artırıyor ve besleme özetleri çoğu haber için yeterli. Gövdenin
 * gerçekten fark yarattığı durumlar ölçülünce bu karar yeniden değerlendirilmeli.
 */
class LlmNewsExtractor implements NewsExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmNewsExtractor.class);

    static final String PURPOSE = "news-analysis";

    /** Modele gönderilen metin için üst sınır — kaçak uzunlukta bir besleme maliyeti patlatmasın. */
    private static final int MAX_INPUT_CHARS = 4_000;

    private static final OutputSchema SCHEMA = OutputSchema.named("haber_analizi")
            .number("sentiment", -1, 1,
                    "Haberin fiyat üzerindeki beklenen yönü. -1 çok olumsuz, 0 nötr, +1 çok olumlu")
            .requiredField()
            .number("materiality", 0, 1,
                    "Haberin önemi. 0 gürültü, 1 piyasayı hareket ettirecek ölçüde önemli")
            .requiredField()
            .enumeration("eventType", Arrays.stream(EventType.values()).map(Enum::name).toList(),
                    "Olay türü")
            .requiredField()
            .enumeration("timeHorizon",
                    Arrays.stream(NewsAnalysis.TimeHorizon.values()).map(Enum::name).toList(),
                    "Etkinin ne kadar sürmesi beklendiği")
            .bool("speculation",
                    "Haber doğrulanmış bir olguyu mu anlatıyor (false), yoksa söylenti, tahmin "
                            + "ya da yorum mu (true)")
            .stringArray("entities",
                    "Haberin doğrudan ilgilendirdiği kripto varlıkların sembolleri, ör. BTC, ETH. "
                            + "Sadece açıkça geçenleri yaz, çıkarım yapma")
            .string("summary", "Haberin tek cümlelik özeti, en fazla 200 karakter")
            .build();

    private static final String INSTRUCTION = """
            Aşağıdaki finans haberini çözümle ve şu alanları doldur: sentiment, materiality, \
            eventType, timeHorizon, speculation, entities, summary.

            Değerlendirme notları:
            - sentiment, haberin ilgilendirdiği varlığın fiyatı üzerindeki beklenen yön olmalı; \
            haberin genel tonu değil. Bir şirketin iflası rakibi için olumlu olabilir.
            - Olumsuzlamaya dikkat et: "reddedilmedi" ile "reddedildi" zıt yönlerdir.
            - materiality için: rutin fiyat yorumları ve tekrar eden analizler düşük (0.0-0.2), \
            regülasyon kararları ve güvenlik olayları yüksek (0.7-1.0).
            - speculation, haberin kaynağı belirsiz bir söylenti ya da bir tahmin/yorum ise true.
            - entities yalnızca metinde açıkça geçen sembolleri içersin.""";

    private final LlmClient llm;
    private final NewsExtractor fallback;

    LlmNewsExtractor(LlmClient llm, NewsExtractor fallback) {
        this.llm = llm;
        this.fallback = fallback;
    }

    @Override
    public String extractorId() {
        return "llm:" + llm.modelId();
    }

    @Override
    public NewsAnalysis analyze(RawNewsItem item) {
        try {
            LlmResult result = llm.complete(buildCall(item));
            return toAnalysis(result, item);
        } catch (LlmException e) {
            // Uyarı seviyesi bilinçli: yedeğe düşmek sessiz geçilecek bir olay değil. Bu
            // satırların sıklaşması ya bütçenin dolduğunu ya da modele erişimin bozulduğunu
            // gösterir; ikisi de fark edilmesi gereken durumlar.
            log.warn("LLM haber çıkarımı başarısız, kural tabanlı yedeğe düşülüyor: url={} neden={}",
                    item.url(), e.getMessage());
            return fallback.analyze(item).withExtractorId(fallback.extractorId());
        }
    }

    private LlmCall buildCall(RawNewsItem item) {
        return LlmCall.forPurpose(PURPOSE)
                .instruction(INSTRUCTION)
                .untrustedData(newsText(item))
                .schema(SCHEMA)
                .maxOutputTokens(400)
                .meta("url", item.url())
                .meta("publishedAt", item.publishedAt() == null ? null : item.publishedAt().toString())
                .build();
    }

    /**
     * Modele gidecek metin. Gövde bilinçli olarak dışarıda (bkz. sınıf açıklaması); başlık ve
     * özet birleştirilip sınırlandırılıyor.
     */
    private static String newsText(RawNewsItem item) {
        String text = "Başlık: " + item.title();
        if (item.summary() != null && !item.summary().isBlank()) {
            text += "\nÖzet: " + item.summary();
        }
        return text.length() <= MAX_INPUT_CHARS ? text : text.substring(0, MAX_INPUT_CHARS);
    }

    private NewsAnalysis toAnalysis(LlmResult result, RawNewsItem item) {
        // sentiment ve materiality zorunlu alanlar; doğrulayıcı sınırların içinde olduklarını
        // ve var olduklarını garanti ediyor. Diğerleri eksik gelebilir, varsayılana düşerler.
        double sentiment = result.number("sentiment");
        double materiality = result.number("materiality");
        EventType eventType = result.enumValue("eventType", EventType.class, EventType.OTHER);
        NewsAnalysis.TimeHorizon horizon = result.enumValue("timeHorizon",
                NewsAnalysis.TimeHorizon.class, NewsAnalysis.TimeHorizon.DAYS);
        boolean speculation = result.has("speculation") && result.bool("speculation");

        String summary = result.has("summary") ? result.string("summary") : item.summary();

        return new NewsAnalysis(sentiment, materiality, eventType, entityIds(result), summary,
                speculation, horizon, extractorId());
    }

    /**
     * Model sembolleri serbest metin olarak veriyor; ontoloji {@code ASSET:BTC} biçimini
     * bekliyor. Çeviri burada yapılıyor ve tanınmayan semboller <em>atılıyor</em>: modelin
     * uydurduğu bir sembolü ontolojiye yazmak, olmayan bir varlık yaratır.
     */
    private static List<String> entityIds(LlmResult result) {
        return result.stringList("entities").stream()
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .map(s -> s.startsWith("ASSET:") ? s.substring("ASSET:".length()) : s)
                .filter(s -> s.matches("[A-Z0-9]{2,10}"))
                .distinct()
                .map(s -> "ASSET:" + s)
                .toList();
    }
}
