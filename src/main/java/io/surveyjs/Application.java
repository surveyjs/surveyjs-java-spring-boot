package io.surveyjs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The SurveyJS backend integration examples on Spring Boot. Each example is one controller in
 * {@code io.surveyjs.examples} and one template in {@code templates/examples/}; README.md lists them.
 *
 * <p>{@code ./mvnw spring-boot:run} or {@code java -jar target/surveyjs-java-spring-boot.jar}, from the repository root.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class Application {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(Application.class);
        // Before Spring creates anything: refuse a busy port, and find out which checkout this is
        app.addListeners(new StartupGuard());
        app.run(args);
    }
}
