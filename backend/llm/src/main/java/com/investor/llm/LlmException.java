package com.investor.llm;

/**
 * LLM çağrısı başarısız.
 *
 * <p>Çağıranın bunu yakalayıp <em>çekimser kalması</em> beklenir. Bir model cevabı
 * alınamadığında varsayılan bir tahmin uydurmak, sistemin en tehlikeli davranışı olurdu:
 * güvenilirlik skoru sahte olur, kalibrasyon bozulur ve karar motoru olmayan bir kanıta
 * dayanır. Cevap yoksa cevap yoktur.
 */
public class LlmException extends RuntimeException {

    private final boolean retryable;

    public LlmException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public LlmException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** Geçici bir sorun mu (ağ, hız sınırı) yoksa kalıcı mı (şema, yetki). */
    public boolean retryable() {
        return retryable;
    }
}
