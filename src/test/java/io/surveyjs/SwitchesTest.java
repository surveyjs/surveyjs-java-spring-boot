package io.surveyjs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.surveyjs.examples.CreatorLoadSaveController;
import io.surveyjs.examples.LintDefinitionController;
import io.surveyjs.examples.SaveResponseController;
import io.surveyjs.examples.ValidateResponseController;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VALIDATE_RESPONSES and LINT_DEFINITIONS accept 1, on, yes and true, as on every platform, and swap the controller
 * that is mapped: ValidateResponseController for SaveResponseController on POST /api/responses, LintDefinitionController
 * for CreatorLoadSaveController on PUT /api/forms/{id}. Off, blank or unset keeps the plain ones. Each case starts the
 * whole app (port 0, its own DATA_DIR), with the switches given by their cross-platform names.
 */
class SwitchesTest {

    @ParameterizedTest(name = "VALIDATE_RESPONSES={0} LINT_DEFINITIONS={1}")
    @CsvSource(nullValues = "unset", value = {
        "1,     on,    true,  true",
        "on,    yes,   true,  true",
        "yes,   true,  true,  true",
        "true,  1,     true,  true",
        "false, unset, false, false",
        "unset, false, false, false",
        "0,     off,   false, false",
        "no,    ' ',   false, false",
    })
    void switchesSwapTheControllers(String validate, String lint, boolean validating, boolean linting) throws Exception {
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--surveyjs.data-dir=" + TestSupport.newDataDir(), "--spring.main.banner-mode=off"));
        if (validate != null) {
            args.add("--VALIDATE_RESPONSES=" + validate);
        }
        if (lint != null) {
            args.add("--LINT_DEFINITIONS=" + lint);
        }
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class).web(WebApplicationType.SERVLET)
                .run(args.toArray(String[]::new))) {
            assertThat(context.getBeanNamesForType(ValidateResponseController.class)).hasSize(validating ? 1 : 0);
            assertThat(context.getBeanNamesForType(SaveResponseController.class)).hasSize(validating ? 0 : 1);
            assertThat(context.getBeanNamesForType(LintDefinitionController.class)).hasSize(linting ? 1 : 0);
            assertThat(context.getBeanNamesForType(CreatorLoadSaveController.class)).hasSize(linting ? 0 : 1);
            assertThat(mapped(context, RequestMethod.POST, "/api/responses")).containsExactly(validating ? ValidateResponseController.class : SaveResponseController.class);
            assertThat(mapped(context, RequestMethod.PUT, "/api/forms/{id}")).containsExactly(linting ? LintDefinitionController.class : CreatorLoadSaveController.class);
        }
    }

    /** The controller classes that handle this method and path. */
    static List<Class<?>> mapped(ConfigurableApplicationContext context, RequestMethod method, String path) {
        RequestMappingHandlerMapping mapping = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        List<Class<?>> handlers = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            if (info.getPatternValues().contains(path) && info.getMethodsCondition().getMethods().contains(method)) {
                handlers.add(entry.getValue().getBeanType());
            }
        }
        return handlers;
    }
}
