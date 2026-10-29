package com.jtac.shared.web;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the single-page application from {@code classpath:/static} when it is packaged
 * into the jar ({@code ./gradlew bootJar -PwithFrontend}). Unknown client-side routes
 * fall back to {@code index.html}; API and actuator paths never do, so they keep
 * returning problem responses.
 *
 * <p>No resource cache is used: with a fallback, every distinct unknown path would
 * otherwise become a new cache entry (unbounded memory growth from anonymous requests).
 */
@Configuration(proxyBeanMethods = false)
class SpaResourceConfiguration implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(false)
                .addResolver(new SpaFallbackResolver());
    }

    static final class SpaFallbackResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            // super.getResource keeps PathResourceResolver's checks (resource must stay under the location).
            Resource requested = super.getResource(resourcePath, location);
            if (requested != null) {
                return requested;
            }
            boolean serverPath = resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")
                    || resourcePath.startsWith("mcp/") || resourcePath.startsWith("oauth/")
                    || resourcePath.startsWith(".well-known/");
            boolean looksLikeFile = resourcePath.substring(resourcePath.lastIndexOf('/') + 1).contains(".");
            if (serverPath || looksLikeFile) {
                return null;
            }
            return super.getResource("index.html", location);
        }
    }
}
