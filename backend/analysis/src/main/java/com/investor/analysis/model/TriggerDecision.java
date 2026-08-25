package com.investor.analysis.model;

import java.util.List;

/**
 * Kapının kararı: tur açılsın mı, hangi sebeple.
 *
 * <p>Kapalı kalan turlar da anlamlı bir çıktı: {@code reasons} boşken sistem "bakacak
 * bir şey yok" demiş oluyor ve bu, aylık maliyetin %95'ini oluşturan tasarrufun kendisi.
 */
public record TriggerDecision(boolean open, List<Trigger> reasons) {

    public TriggerDecision {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static TriggerDecision closed() {
        return new TriggerDecision(false, List.of());
    }

    public static TriggerDecision opened(List<Trigger> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            throw new IllegalArgumentException("sebepsiz tur açılamaz");
        }
        return new TriggerDecision(true, reasons);
    }

    public boolean hasType(Trigger.Type type) {
        return reasons.stream().anyMatch(r -> r.type() == type);
    }

    /** Kayda ve loga giden kısa özet. */
    public String summary() {
        if (!open) {
            return "kapalı";
        }
        return reasons.stream().map(r -> r.type().name()).distinct()
                .reduce((a, b) -> a + "," + b).orElse("");
    }
}
