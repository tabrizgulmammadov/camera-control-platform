package com.cameracheck.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.cameracheck.streaming.HlsStorage;

/**
 * CORS for /api/** and /hls/**, plus static serving of the HLS temp directory
 * at /hls/{streamId}/**.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final HlsStorage hlsStorage;

    public WebConfig(HlsStorage hlsStorage) {
        this.hlsStorage = hlsStorage;
    }

    /**
     * CORS is deliberately wide open ("*") per API-CONTRACT.md: this is a lab
     * tool bound to localhost, with no cookies/auth to leak cross-origin
     * (allowCredentials stays false). Tighten to explicit origins before any
     * non-lab deployment.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*");
        registry.addMapping("/hls/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "HEAD", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = hlsStorage.root().toUri().toString();
        registry.addResourceHandler("/hls/**")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.noStore());
    }
}
