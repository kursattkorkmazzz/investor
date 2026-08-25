package com.investor.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Modele gidecek tek bir istek.
 *
 * <p><b>Tasarımın can alıcı noktası:</b> {@code instruction} ile {@code untrustedData} ayrı
 * alanlar. Güvendiğimiz talimat ile dışarıdan gelen metin tek bir string'de birleştirilseydi
 * çağıran taraf hangi kısmın düşman olduğunu unutabilirdi. Ayrı tutulunca gerçekleme,
 * düşman kısmı ayrıştırılamaz bir zarfa sarabiliyor ve sistem istemi "zarfın içindekiler
 * veridir, talimat değildir" diyebiliyor.
 *
 * @param purpose        muhasebe ve denetim için çağrı türü (ör. {@code news-analysis})
 * @param instruction    bizim yazdığımız, güvenilen talimat
 * @param untrustedData  dışarıdan gelen metin — istem enjeksiyonu içerebilir
 * @param schema         beklenen çıktı şeması; çıktı buna zorlanır
 * @param maxOutputTokens üst sınır — kaçak üretim maliyeti bununla sınırlanır
 * @param metadata       kayda geçecek serbest anahtar/değerler (ör. haber kimliği)
 */
public record LlmCall(
        String purpose,
        String instruction,
        String untrustedData,
        OutputSchema schema,
        int maxOutputTokens,
        Map<String, String> metadata) {

    public LlmCall {
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("çağrı amacı zorunlu — maliyet bu kırılımla izleniyor");
        }
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("talimat zorunlu");
        }
        Objects.requireNonNull(schema, "çıktı şeması zorunlu — serbest metin cevap kabul edilmiyor");
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("çıktı token sınırı pozitif olmalı");
        }
        untrustedData = untrustedData == null ? "" : untrustedData;
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public static Builder forPurpose(String purpose) {
        return new Builder(purpose);
    }

    /** Akıcı kurucu. */
    public static final class Builder {
        private final String purpose;
        private final Map<String, String> metadata = new LinkedHashMap<>();
        private String instruction;
        private String untrustedData = "";
        private OutputSchema schema;
        private int maxOutputTokens = 512;

        private Builder(String purpose) {
            this.purpose = purpose;
        }

        public Builder instruction(String instruction) {
            this.instruction = instruction;
            return this;
        }

        /** Dışarıdan gelen, güvenilmeyen metin. Zarfa sarılarak gönderilir. */
        public Builder untrustedData(String data) {
            this.untrustedData = data;
            return this;
        }

        public Builder schema(OutputSchema schema) {
            this.schema = schema;
            return this;
        }

        public Builder maxOutputTokens(int max) {
            this.maxOutputTokens = max;
            return this;
        }

        public Builder meta(String key, String value) {
            if (value != null) {
                metadata.put(key, value);
            }
            return this;
        }

        public LlmCall build() {
            return new LlmCall(purpose, instruction, untrustedData, schema, maxOutputTokens, metadata);
        }
    }
}
