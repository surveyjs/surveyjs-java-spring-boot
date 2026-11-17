package io.surveyjs.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import io.surveyjs.integration.SurveyJsService;
import io.surveyjs.integration.SurveyJsService.ServiceAnswer;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generate PDFs: GET /api/claims/{id}/pdf, a claim filled into its definition by the SurveyJS service.
 */
@RestController
public class ServerPdfController {

    private static final Path DEFINITIONS = Path.of("shared", "definitions");

    /** A claim response: the schema version it was submitted with, and its answers. */
    public record Claim(String definitionVersion, String data) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SurveyJsService surveyjs;

    public ServerPdfController(JdbcClient jdbc, JsonMapper json, SurveyJsService surveyjs) {
        this.jdbc = jdbc;
        this.json = json;
        this.surveyjs = surveyjs;
    }

    // #region sjs:server-pdf.server
    // GET /api/claims/{id}/pdf — ask the SurveyJS service for the filled form and pass the PDF through
    @GetMapping("/api/claims/{id:\\d+}/pdf")
    public ResponseEntity<byte[]> claimPdf(@PathVariable long id) throws IOException {
        Claim claim = jdbc.sql("SELECT definition_version, data FROM responses WHERE form_id = 'claim' AND id = ?").param(id)
                .query(Claim.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown claim"));
        String definition = switch (claim.definitionVersion()) {   // use the schema version recorded with the response
            case "v1", "v2" -> Files.readString(DEFINITIONS.resolve("relational-storage." + claim.definitionVersion() + ".json"));
            case null -> jdbc.sql("SELECT json FROM forms WHERE key = 'claim'").query(String.class).single();
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "The schema version for this claim is unavailable");
        };

        ServiceAnswer pdf = surveyjs.post("/pdf", Map.of("schema", json.readTree(definition), "response", json.readTree(claim.data())), Duration.ofSeconds(60));
        if (pdf.status() != 200 || !pdf.hasContentType("application/pdf") || !pdf.startsWith("%PDF-")) {   // a 200 alone isn't a PDF
            throw surveyjs.unexpected("/pdf", pdf);
        }

        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("claim-" + id + ".pdf").build().toString())
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(pdf.bytes());
    }
    // #endregion
}
