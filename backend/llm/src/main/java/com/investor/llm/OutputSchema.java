package com.investor.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Modelden beklenen çıktının şeması — sağlayıcıdan bağımsız.
 *
 * <p>Neden LangChain4j'in {@code JsonSchemaElement}'ini doğrudan kullanmıyoruz: o tip porta
 * sızarsa sağlayıcı değişimi imkânsızlaşır (ADR-0008). Ayrıca bu gösterim iki yere birden
 * çevrilebiliyor — sağlayıcının {@code response_format} alanına <em>ve</em> istemin içine metin
 * olarak. İkincisi katı şema desteği olmayan uç noktalar için yedek yol; demo uç noktasının
 * ne desteklediğini bilmiyoruz, o yüzden ikisi de lazım.
 *
 * <p>Kasten düz: tek seviye nesne, alanlar ilkel ya da metin dizisi. İç içe şemaya ihtiyaç
 * duyduğumuz anda eklenecek — şimdi eklemek kullanılmayan kod olurdu.
 */
public record OutputSchema(String name, Map<String, Field> fields, List<String> required) {

    public OutputSchema {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("şema adı zorunlu");
        }
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("şema en az bir alan içermeli: " + name);
        }
        fields = Map.copyOf(new LinkedHashMap<>(fields));
        required = required == null ? List.of() : List.copyOf(required);
        for (String key : required) {
            if (!fields.containsKey(key)) {
                throw new IllegalArgumentException("zorunlu alan şemada yok: " + key);
            }
        }
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    /** Alan türleri. Sağlayıcı bunları kendi şema tiplerine çevirir. */
    public enum Kind {
        STRING,
        NUMBER,
        INTEGER,
        BOOLEAN,
        ENUM,
        STRING_ARRAY
    }

    /**
     * Tek bir alan.
     *
     * @param enumValues yalnızca {@link Kind#ENUM} için; kapalı küme
     * @param min        yalnızca sayısal türler için alt sınır (doğrulamada kullanılır)
     * @param max        yalnızca sayısal türler için üst sınır
     */
    public record Field(
            Kind kind,
            String description,
            List<String> enumValues,
            Double min,
            Double max) {

        public Field {
            Objects.requireNonNull(kind, "alan türü zorunlu");
            enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
            if (kind == Kind.ENUM && enumValues.isEmpty()) {
                throw new IllegalArgumentException("enum alanı değer kümesi olmadan tanımlanamaz");
            }
            if (kind != Kind.ENUM && !enumValues.isEmpty()) {
                throw new IllegalArgumentException("enum olmayan alanda değer kümesi olamaz");
            }
            boolean numeric = kind == Kind.NUMBER || kind == Kind.INTEGER;
            if (!numeric && (min != null || max != null)) {
                throw new IllegalArgumentException("sayısal olmayan alanda sınır olamaz");
            }
            if (min != null && max != null && min > max) {
                throw new IllegalArgumentException("alt sınır üst sınırdan büyük olamaz");
            }
        }

        public Optional<Double> minValue() {
            return Optional.ofNullable(min);
        }

        public Optional<Double> maxValue() {
            return Optional.ofNullable(max);
        }
    }

    /** Şemanın istem içine gömülebilir metin gösterimi (katı şema desteklenmediğinde yedek). */
    public String describe() {
        StringBuilder sb = new StringBuilder("{\n");
        Set<String> req = Set.copyOf(required);
        int i = 0;
        for (Map.Entry<String, Field> e : fields.entrySet()) {
            Field f = e.getValue();
            sb.append("  \"").append(e.getKey()).append("\": ").append(typeHint(f));
            sb.append(req.contains(e.getKey()) ? "  // zorunlu" : "  // isteğe bağlı");
            if (f.description() != null && !f.description().isBlank()) {
                sb.append(" — ").append(f.description());
            }
            if (++i < fields.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        return sb.append('}').toString();
    }

    private static String typeHint(Field f) {
        return switch (f.kind()) {
            case STRING -> "<metin>";
            case BOOLEAN -> "<true|false>";
            case STRING_ARRAY -> "[<metin>, ...]";
            case ENUM -> String.join("|", f.enumValues());
            case NUMBER, INTEGER -> {
                String base = f.kind() == Kind.INTEGER ? "<tam sayı" : "<ondalık";
                if (f.min() != null || f.max() != null) {
                    base += " " + (f.min() == null ? "-∞" : f.min()) + ".." + (f.max() == null ? "+∞" : f.max());
                }
                yield base + ">";
            }
        };
    }

    /** Akıcı kurucu. */
    public static final class Builder {
        private final String name;
        private final Map<String, Field> fields = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder string(String key, String description) {
            return put(key, new Field(Kind.STRING, description, null, null, null));
        }

        public Builder number(String key, double min, double max, String description) {
            return put(key, new Field(Kind.NUMBER, description, null, min, max));
        }

        public Builder integer(String key, String description) {
            return put(key, new Field(Kind.INTEGER, description, null, null, null));
        }

        public Builder bool(String key, String description) {
            return put(key, new Field(Kind.BOOLEAN, description, null, null, null));
        }

        public Builder enumeration(String key, List<String> values, String description) {
            return put(key, new Field(Kind.ENUM, description, values, null, null));
        }

        public Builder stringArray(String key, String description) {
            return put(key, new Field(Kind.STRING_ARRAY, description, null, null, null));
        }

        /** Son eklenen alanı zorunlu işaretler. */
        public Builder requiredField() {
            if (fields.isEmpty()) {
                throw new IllegalStateException("önce alan eklenmeli");
            }
            String last = fields.keySet().stream().reduce((a, b) -> b).orElseThrow();
            if (!required.contains(last)) {
                required.add(last);
            }
            return this;
        }

        private Builder put(String key, Field field) {
            if (fields.putIfAbsent(key, field) != null) {
                throw new IllegalArgumentException("alan iki kez tanımlandı: " + key);
            }
            return this;
        }

        public OutputSchema build() {
            return new OutputSchema(name, fields, required);
        }
    }
}
