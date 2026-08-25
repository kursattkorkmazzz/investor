package com.investor.llm.internal;

import java.util.Map;

import com.investor.llm.OutputSchema;

import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

/**
 * Kendi şema gösterimimizi LangChain4j'in şema tiplerine çevirir.
 *
 * <p>Bu sınıf, LangChain4j'in bu modülde kapalı kalmasını sağlayan iki yerden biri
 * (diğeri {@link LangChain4jLlmClient}). Sağlayıcı değişirse yalnızca bu ikisi değişir.
 *
 * <p><b>Şema bir güvence değil, güçlü bir ipucu.</b> Doğrulanan davranış (bkz.
 * {@code LlmPipelineGateTest}): LangChain4j {@code response_format.json_schema} içinde
 * {@code "strict": false} gönderiyor ve {@code additionalProperties} alanını hiç
 * yazmıyor. Yani sunucu tarafında zorlama yok — model şemayı ihlal edebilir, fazladan
 * alan ekleyebilir, sınırların dışına çıkabilir. Zorlamayı yapan tek yer
 * {@link ResponseValidator}. Bu, güvenlik modelinin de dayandığı nokta: şemaya değil,
 * kendi doğrulamamıza güveniyoruz.
 *
 * <p>OpenAI'ın gerçek katı modu ({@code strict: true}) her alanın {@code required}
 * listesinde olmasını şart koşuyor. İsteğe bağlı alanlarımız var ve modeli dolduramadığı
 * bir alanı doldurmaya zorlamak, uydurulmuş değer üretmekten başka bir şeye yaramaz —
 * bu yüzden katı moda geçmiyoruz.
 *
 * <p>Sayısal sınırlar (min/max) da JSON Schema'ya çevrilmiyor: LangChain4j'in
 * {@code JsonNumberSchema}'sında karşılığı yok. Sınırlar açıklamaya yazılıyor ve
 * doğrulayıcı tarafından zorlanıyor.
 */
final class SchemaTranslator {

    private SchemaTranslator() {
    }

    static ResponseFormat toResponseFormat(OutputSchema schema) {
        return ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder()
                        .name(PromptEnvelope.schemaName(schema))
                        .rootElement(toObjectSchema(schema))
                        .build())
                .build();
    }

    private static JsonObjectSchema toObjectSchema(OutputSchema schema) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        for (Map.Entry<String, OutputSchema.Field> entry : schema.fields().entrySet()) {
            builder.addProperty(entry.getKey(), toElement(entry.getValue()));
        }
        if (!schema.required().isEmpty()) {
            builder.required(schema.required());
        }
        // additionalProperties=false isteniyor ama LangChain4j bunu katı mod dışında tele
        // yazmıyor. İstek yine de belirtiliyor: sağlayıcı ileride desteklerse çalışsın.
        // Şema dışı alanların gerçekten atılması doğrulayıcının işi.
        return builder.additionalProperties(false).build();
    }

    private static JsonSchemaElement toElement(OutputSchema.Field field) {
        String description = describe(field);
        return switch (field.kind()) {
            case STRING -> JsonStringSchema.builder().description(description).build();
            case BOOLEAN -> JsonBooleanSchema.builder().description(description).build();
            case INTEGER -> JsonIntegerSchema.builder().description(description).build();
            case NUMBER -> JsonNumberSchema.builder().description(description).build();
            case ENUM -> JsonEnumSchema.builder()
                    .enumValues(field.enumValues())
                    .description(description)
                    .build();
            case STRING_ARRAY -> JsonArraySchema.builder()
                    .items(JsonStringSchema.builder().build())
                    .description(description)
                    .build();
        };
    }

    /** Sınırlar şemada taşınamadığı için açıklamaya ekleniyor — model çoğu zaman uyuyor. */
    private static String describe(OutputSchema.Field field) {
        String base = field.description() == null ? "" : field.description();
        if (field.min() == null && field.max() == null) {
            return base.isBlank() ? null : base;
        }
        String range = " (aralık: " + (field.min() == null ? "-∞" : field.min())
                + " … " + (field.max() == null ? "+∞" : field.max()) + ")";
        return base + range;
    }
}
