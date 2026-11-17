package io.surveyjs.examples;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import io.surveyjs.integration.FileType;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Extract Responses from Documents: a blank, fillable work order, and the answers the SurveyJS service reads from a
 * completed one. Nothing is saved here: the visitor reviews the draft and the page posts it to /api/responses.
 */
@RestController
public class ExtractFromPaperController {

    private static final long MAX_BYTES = 5L * 1024 * 1024;

    /** The draft response: answers in the form's shape, a confidence per field, the document's id or null. */
    public record Draft(JsonNode answers, JsonNode confidence, JsonNode uniqueId) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SurveyJsService surveyjs;

    public ExtractFromPaperController(JdbcClient jdbc, JsonMapper json, SurveyJsService surveyjs) {
        this.jdbc = jdbc;
        this.json = json;
        this.surveyjs = surveyjs;
    }

    /** The stored work-order form: the blank PDF, the extraction and the review form use the same definition. */
    private Optional<JsonNode> workOrder() {
        return jdbc.sql("SELECT json FROM forms WHERE key = 'work-order'").query(String.class).optional().map(json::readTree);
    }

    // #region sjs:extract-from-paper.template
    // Generate a blank, fillable PDF using the same schema as extraction
    @GetMapping("/api/work-orders/blank.pdf")
    public ResponseEntity<byte[]> blankWorkOrder() {
        JsonNode form = workOrder().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown work order form"));
        ServiceAnswer pdf = surveyjs.post("/pdf", Map.of("schema", form), Duration.ofSeconds(60));
        if (pdf.status() != 200 || !pdf.hasContentType("application/pdf") || !pdf.startsWith("%PDF-")) {   // a 200 alone isn't a PDF
            throw surveyjs.unexpected("/pdf", pdf);
        }

        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("work-order.pdf").build().toString())
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(pdf.bytes());
    }
    // #endregion

    // #region sjs:extract-from-paper.server
    // POST /api/work-orders/extract — send the uploaded scan to the SurveyJS service; return a draft response
    @PostMapping(path = "/api/work-orders/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Draft extractWorkOrder(MultipartHttpServletRequest request) throws IOException {
        MultipartFile scan = request.getFile("scan");
        if (scan == null || scan.getSize() > MAX_BYTES) {   // missing, a text field (not a file), or too large
            throw scan == null && request.getParameter("scan") == null
                    ? new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload the scan in a field named \"scan\"")
                    : new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "The scan must be a file of 5 MB or less");
        }
        if (FileType.detect(scan) == null) {   // by the first bytes, never the client's Content-Type
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file is not a PDF, PNG, JPEG, WebP or GIF document");
        }
        ServiceAnswer out = surveyjs.post("/extract", Map.of("schema", workOrder().orElseThrow(), "document", Base64.getEncoder().encodeToString(scan.getBytes())), Duration.ofSeconds(120));
        JsonNode result = out.json();   // JSON with base64 went out, not multipart; an answer that isn't JSON fails closed
        if (out.status() == 200 && result != null && result.path("data").isObject()) {   // nothing is saved: a person reviews the draft
            return new Draft(result.get("data"), result.has("confidence") ? result.get("confidence") : json.createArrayNode(), result.get("uniqueId"));
        }
        throw switch (out.status() + " " + (result == null ? "" : result.path("error").asString(""))) {
            case "400 INVALID_DOCUMENT" -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file is not a PDF, PNG, JPEG, WebP or GIF document");
            case "503 AI_NOT_CONFIGURED" -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The SurveyJS service has no AI provider configured");
            case "502 EXTRACTION_FAILED" -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not extract the response from the document");
            default -> surveyjs.unexpectedExtraction(out);   // fail closed, logged by status only
        };
    }
    // #endregion
}
