package com.edischema.scraper;

/** Raised when a page cannot be fetched or its structure cannot be parsed. */
public class ScrapeException extends RuntimeException {
    public ScrapeException(String message) {
        super(message);
    }

    public ScrapeException(String message, Throwable cause) {
        super(message, cause);
    }
}
