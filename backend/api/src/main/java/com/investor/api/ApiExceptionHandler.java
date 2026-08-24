package com.investor.api;

import java.net.URI;

import com.investor.ontology.OntologyException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Ontoloji hatalarını HTTP durumlarına çevirir.
 *
 * <p>Ayrım anlamlı: {@code 409} "isteğin kendisi geçerli ama mevcut durumla çelişiyor"
 * demek — istemci geri çekme yapıp tekrar deneyebilir. {@code 400} ise istek yanlış.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String BASE = "https://investor.local/problems/";

    @ExceptionHandler(OntologyException.NotFound.class)
    public ProblemDetail notFound(OntologyException.NotFound e) {
        return problem(HttpStatus.NOT_FOUND, "Bulunamadı", e.getMessage(), "not-found");
    }

    @ExceptionHandler(OntologyException.SchemaViolation.class)
    public ProblemDetail schemaViolation(OntologyException.SchemaViolation e) {
        return problem(HttpStatus.BAD_REQUEST, "Şema ihlali", e.getMessage(), "schema-violation");
    }

    @ExceptionHandler(OntologyException.TemporalConflict.class)
    public ProblemDetail temporalConflict(OntologyException.TemporalConflict e) {
        ProblemDetail detail = problem(HttpStatus.CONFLICT, "Zaman çakışması", e.getMessage(),
                "temporal-conflict");
        detail.setProperty("hint",
                "Geçmişe yazmadan önce mevcut kaydı geri çekin (retract) veya kapatın (close).");
        return detail;
    }

    @ExceptionHandler(OntologyException.DuplicateObject.class)
    public ProblemDetail duplicate(OntologyException.DuplicateObject e) {
        return problem(HttpStatus.CONFLICT, "Zaten var", e.getMessage(), "duplicate");
    }

    @ExceptionHandler(OntologyException.class)
    public ProblemDetail ontology(OntologyException e) {
        log.warn("Ontoloji hatası", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Ontoloji hatası", e.getMessage(), "ontology");
    }

    /** Bozuk ya da şemaya uymayan JSON gövdesi. Sebebi gizlemek hata ayıklamayı zorlaştırıyor. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ProblemDetail unreadableBody(org.springframework.http.converter.HttpMessageNotReadableException e) {
        Throwable root = e.getCause() == null ? e : e.getCause();
        log.debug("İstek gövdesi okunamadı", e);
        return problem(HttpStatus.BAD_REQUEST, "Gövde okunamadı", root.getMessage(), "unreadable-body");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail illegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Geçersiz istek", e.getMessage(), "invalid-request");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String slug) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(BASE + slug));
        return problem;
    }
}
