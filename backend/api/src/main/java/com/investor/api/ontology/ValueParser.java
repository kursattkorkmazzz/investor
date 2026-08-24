package com.investor.api.ontology;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.investor.ontology.OntologyException;
import com.investor.ontology.model.Cardinality;
import com.investor.ontology.model.DataType;
import com.investor.ontology.model.PropertyTypeDef;
import com.investor.ontology.model.Value;
import com.investor.ontology.model.Values;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * JSON gövdesinden gelen ham değerleri alanın şemadaki tipine çevirir.
 *
 * <p>Çeviri şemaya bakarak yapılır, gelen JSON'un görünen tipine göre değil: bir DECIMAL
 * alan için {@code 19500000} da {@code "19500000"} da kabul edilir ve ikisi de
 * {@link BigDecimal} olur. Ondalık değerler her zaman metin üzerinden {@code BigDecimal}'e
 * geçirilir — JSON'un double'ına uğramaz.
 */
@Component
public class ValueParser {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public Value parse(PropertyTypeDef property, Object raw) {
        if (raw == null) {
            throw new OntologyException.SchemaViolation(
                    "'%s' alanına null verilemez; değeri kaldırmak için kapatma veya geri çekme kullanın"
                            .formatted(property.apiName()));
        }
        return switch (property.dataType()) {
            case STRING, TEXT, ENUM -> Values.text(raw.toString());
            case INTEGER, DECIMAL -> Values.number(decimal(property, raw));
            case BOOLEAN -> Values.bool(bool(property, raw));
            case TIMESTAMP -> Values.timestamp(instant(property, raw));
            case DATE -> Values.date(localDate(property, raw));
            case JSON -> Values.json(jsonMapper.writeValueAsString(raw));
            case REFERENCE -> Values.ref(uuid(property, raw));
        };
    }

    public List<Value> parseList(PropertyTypeDef property, Object raw) {
        if (!(raw instanceof List<?> items)) {
            throw new OntologyException.SchemaViolation(
                    "'%s' alanı LIST kardinaliteli; dizi bekleniyor".formatted(property.apiName()));
        }
        List<Value> values = new ArrayList<>(items.size());
        items.forEach(item -> values.add(parse(property, item)));
        return values;
    }

    /** Alanın kardinalitesine göre tekil ya da liste olarak çözer. */
    public Object parseAny(PropertyTypeDef property, Object raw) {
        return property.cardinality() == Cardinality.LIST ? parseList(property, raw) : parse(property, raw);
    }

    // ------------------------------------------------------------------

    private static BigDecimal decimal(PropertyTypeDef property, Object raw) {
        try {
            if (raw instanceof BigDecimal d) {
                return d;
            }
            // Double üzerinden geçirmemek için her zaman metinden okuyoruz.
            return new BigDecimal(raw.toString());
        } catch (NumberFormatException e) {
            throw invalid(property, raw, DataType.DECIMAL);
        }
    }

    private static boolean bool(PropertyTypeDef property, Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        String text = raw.toString();
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.parseBoolean(text);
        }
        throw invalid(property, raw, DataType.BOOLEAN);
    }

    private static Instant instant(PropertyTypeDef property, Object raw) {
        try {
            return raw instanceof Instant i ? i : Instant.parse(raw.toString());
        } catch (Exception e) {
            throw invalid(property, raw, DataType.TIMESTAMP);
        }
    }

    private static LocalDate localDate(PropertyTypeDef property, Object raw) {
        try {
            if (raw instanceof LocalDate d) {
                return d;
            }
            String text = raw.toString();
            return text.length() > 10
                    ? Instant.parse(text).atZone(ZoneOffset.UTC).toLocalDate()
                    : LocalDate.parse(text);
        } catch (Exception e) {
            throw invalid(property, raw, DataType.DATE);
        }
    }

    private static UUID uuid(PropertyTypeDef property, Object raw) {
        try {
            return raw instanceof UUID u ? u : UUID.fromString(raw.toString());
        } catch (Exception e) {
            throw invalid(property, raw, DataType.REFERENCE);
        }
    }

    private static OntologyException.SchemaViolation invalid(PropertyTypeDef property, Object raw, DataType expected) {
        return new OntologyException.SchemaViolation(
                "'%s' alanı %s bekliyor; '%s' okunamadı".formatted(property.apiName(), expected, raw));
    }

    /** Boş harita yerine null gelirse patlamamak için. */
    public static Map<String, Object> orEmpty(Map<String, Object> map) {
        return map == null ? Map.of() : map;
    }
}
