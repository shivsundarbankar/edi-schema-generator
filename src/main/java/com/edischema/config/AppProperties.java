package com.edischema.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Central configuration for scraping politeness, caching, output locations
 * and the Stedi -> EDISchema mapping rules.
 */
@ConfigurationProperties(prefix = "edischema")
public record AppProperties(
        String userAgent,
        long minRequestIntervalMillis,
        int requestTimeoutSeconds,
        int maxRetries,
        long retryBackoffMillis,
        String cacheDir,
        int cacheTtlHours,
        String outputDir,
        int defaultUnboundedMax,
        boolean skipEnvelopeSegments
) {
}
