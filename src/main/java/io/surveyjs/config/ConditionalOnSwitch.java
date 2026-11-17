package io.surveyjs.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Registers the bean only when a switch such as {@code surveyjs.validate-responses} is on ({@code on = true}) or off
 * ({@code on = false}, the default when unset). Switches accept {@code 1|true|on|yes}, as on every platform:
 * the property is bound as a {@link Boolean} with Spring's conversion. Boot's {@code @ConditionalOnBooleanProperty}
 * compares the text with "true", so {@code VALIDATE_RESPONSES=1} would not count.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
@Conditional(ConditionalOnSwitch.OnSwitch.class)
public @interface ConditionalOnSwitch {

    /** The property, e.g. {@code surveyjs.validate-responses}. */
    String value();

    /** Whether the bean needs the switch on or off. */
    boolean on() default true;

    class OnSwitch implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnSwitch.class.getName());
            String name = (String) attributes.get("value");
            boolean on = (Boolean) attributes.get("on");
            boolean value = Binder.get(context.getEnvironment()).bind(name, Boolean.class).orElse(false);
            return value == on;
        }
    }
}
