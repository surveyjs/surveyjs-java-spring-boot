package io.surveyjs.examples;

import java.util.List;

import io.surveyjs.config.SurveyJsProperties;
import io.surveyjs.integration.AiTranslation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * Translate with AI: POST /api/translate, Creator's strings translated by your OpenAI-compatible provider.
 *
 * <p>The request isn't stored, so a record reads it; integration/AiTranslation calls the provider.
 */
@RestController
public class AiTranslationController {

    /** What Creator's onMachineTranslate sends. */
    public record TranslateRequest(List<String> strings, String from, String to) {
        public TranslateRequest {
            strings = strings == null ? List.of() : strings;
        }
    }

    private final SurveyJsProperties settings;
    private final AiTranslation ai;

    public AiTranslationController(SurveyJsProperties settings, AiTranslation ai) {
        this.settings = settings;
        this.ai = ai;
    }

    // #region sjs:ai-translation.server
    // POST /api/translate — strings in, translated strings out (same order); nothing goes to SurveyJS
    @PostMapping("/api/translate")
    public JsonNode translate(@RequestBody TranslateRequest body) {
        if (settings.aiApiKey() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Set AI_API_KEY to enable translation");
        }

        JsonNode translated = ai.translate(body.strings(), body.from(), body.to());   // any OpenAI-compatible provider
        if (translated == null || !translated.isArray() || translated.size() != body.strings().size()
                || !translated.valueStream().allMatch(JsonNode::isString)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The AI provider returned an unexpected answer");
        }

        return translated;   // what options.callback() takes
    }
    // #endregion
}
