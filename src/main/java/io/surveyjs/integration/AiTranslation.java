package io.surveyjs.integration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.surveyjs.config.SurveyJsProperties;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks any OpenAI-compatible /chat/completions endpoint (AI_BASE_URL, AI_API_KEY, AI_MODEL) to translate Creator's
 * strings. Your provider and your key: nothing goes to SurveyJS. Swap this class for your provider's SDK if you like;
 * the controller only needs a list back. When you copy ai-translation, copy this class too.
 */
@Service
public class AiTranslation {

    private static final Log log = LogFactory.getLog(AiTranslation.class);
    private static final Pattern ARRAY = Pattern.compile("\\[.*]", Pattern.DOTALL);

    private final SurveyJsProperties settings;
    private final JsonMapper json;
    private final RestClient http;

    public AiTranslation(SurveyJsProperties settings, JsonMapper json) {
        this.settings = settings;
        this.json = json;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * The translated strings as the model returned them (an array if all went well), or null when the answer can't be
     * read. An unreachable provider or an HTTP error ends the request here with 502.
     */
    public JsonNode translate(List<String> strings, String from, String to) {
        Map<String, Object> request = Map.of(
                "model", settings.aiModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", "Translate each string from " + from + " to " + to + ". Return a JSON array in the same order."),
                        Map.of("role", "user", "content", json.writeValueAsString(strings))));
        String reply;
        try {
            reply = http.post().uri(settings.aiBaseUrl() + "/chat/completions")
                    .header("Authorization", "Bearer " + settings.aiApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsBytes(request))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            log.error("Translation provider failed: " + e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The translation provider is unavailable. Please try again.");
        }
        try {
            JsonNode content = json.readTree(reply == null ? "" : reply).path("choices").path(0).path("message").path("content");
            // Models sometimes wrap the array in prose or a code fence: take the array itself
            Matcher array = content.isString() ? ARRAY.matcher(content.asString()) : null;
            return array != null && array.find() ? json.readTree(array.group()) : null;
        } catch (JacksonException e) {
            return null;
        }
    }
}
