package io.surveyjs.web;

import java.nio.file.Path;
import java.util.Map;

import javax.sql.DataSource;

import io.surveyjs.config.ConditionalOnSwitch;
import io.surveyjs.config.SurveyJsProperties;
import io.surveyjs.demo.AiLimitInterceptor;
import io.surveyjs.demo.CacheHeaderAdvice;
import io.surveyjs.demo.PageUserInterceptor;
import io.surveyjs.demo.SandboxDataSource;
import io.surveyjs.demo.SandboxFilter;
import io.surveyjs.demo.UploadLimitInterceptor;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Spring MVC around the examples: what the app serves besides them, the request size limit, and (demo mode) the
 * visitor sandbox and the demo's interceptors.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final SurveyJsProperties settings;
    private final PageUserInterceptor pageUser;
    private final CacheHeaderAdvice cacheHeader;

    public WebConfig(SurveyJsProperties settings, PageUserInterceptor pageUser, CacheHeaderAdvice cacheHeader) {
        this.settings = settings;
        this.pageUser = pageUser;
        this.cacheHeader = cacheHeader;
    }

    /**
     * Only these four folders of shared/ (the client modules, definitions, samples and page UI) and the site
     * stylesheet. Spring's resource resolver refuses anything outside each location. Cache-Control: no-cache, so the
     * browser revalidates with Last-Modified instead of Spring Security's default no-store.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        for (String folder : new String[] {"client", "definitions", "samples", "ui"}) {
            registry.addResourceHandler("/shared/" + folder + "/**")
                    .addResourceLocations(Path.of("shared", folder).toAbsolutePath().toUri().toString())
                    .setCacheControl(CacheControl.noCache());
        }
        registry.addResourceHandler("/css/**").addResourceLocations("classpath:/static/css/").setCacheControl(CacheControl.noCache());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Demo only: each example page signs in as its own user, and the countries list says where it came from
        registry.addInterceptor(pageUser).addPathPatterns("/examples/**", "/claim");
        registry.addInterceptor(cacheHeader).addPathPatterns("/api/countries");
        if (settings.demoMode()) {
            // Demo only: the hosted demo's 5 MB upload cap and 20 AI calls per visitor per day
            registry.addInterceptor(new UploadLimitInterceptor()).addPathPatterns("/api/files", "/api/work-orders/extract");
            registry.addInterceptor(new AiLimitInterceptor()).addPathPatterns("/api/translate", "/api/work-orders/extract");
        }
    }

    /** Health: answers without touching the database. */
    @Bean
    public RouterFunction<ServerResponse> up() {
        return RouterFunctions.route().GET("/up", request -> ServerResponse.ok().body(Map.of("status", "ok"))).build();
    }

    /** First of all filters: a body over 24 MB never reaches anything else. */
    @Bean
    public FilterRegistrationBean<BodyLimitFilter> bodyLimitFilter() {
        FilterRegistrationBean<BodyLimitFilter> registration = new FilterRegistrationBean<>(new BodyLimitFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** Demo only: the visitor's sandbox, bound before Spring Security's filter chain signs in the demo user. */
    @Bean
    @ConditionalOnSwitch("surveyjs.demo-mode")
    public FilterRegistrationBean<SandboxFilter> sandboxFilter(DataSource dataSource) {
        FilterRegistrationBean<SandboxFilter> registration = new FilterRegistrationBean<>(new SandboxFilter((SandboxDataSource) dataSource));
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1);
        return registration;
    }
}
