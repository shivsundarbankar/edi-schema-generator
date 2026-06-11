package com.edischema.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Stedi EDI reference URL into its parts. Works for any X12 release:
 *   https://www.stedi.com/edi/x12-004010/850
 *   https://www.stedi.com/edi/x12-005010/856
 *
 * @param baseUrl       e.g. https://www.stedi.com/edi/x12-004010
 * @param releaseToken  e.g. x12-004010
 * @param releaseCode   e.g. 004010
 * @param transactionId e.g. 850
 */
public record ReleaseUrl(String baseUrl, String releaseToken, String releaseCode, String transactionId) {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "^(https?://[^/]+/edi/(x12-(\\d{6})))/(\\d{3})/?$");

    public static ReleaseUrl parse(String url) {
        Matcher m = URL_PATTERN.matcher(url.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "Unsupported URL: " + url + System.lineSeparator()
                    + "Expected a Stedi transaction-set URL such as "
                    + "https://www.stedi.com/edi/x12-004010/850 or "
                    + "https://www.stedi.com/edi/x12-005010/810");
        }
        return new ReleaseUrl(m.group(1), m.group(2), m.group(3), m.group(4));
    }

    public String transactionUrl() {
        return baseUrl + "/" + transactionId;
    }

    public String segmentUrl(String segmentCode) {
        return baseUrl + "/segment/" + segmentCode;
    }
}
