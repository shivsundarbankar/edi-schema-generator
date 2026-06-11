package com.edischema.model;

/**
 * A simple X12 data element definition.
 *
 * @param ref       schema reference id, e.g. E0353 (zero padded to 4 digits)
 * @param number    raw X12 element number, e.g. 353
 * @param name      e.g. "Transaction Set Purpose Code"
 * @param baseType  EDISchema base type: identifier|string|date|time|numeric|decimal|binary
 * @param minLength minimum length
 * @param maxLength maximum length
 */
public record ElementDef(String ref, String number, String name, String baseType,
                         int minLength, int maxLength) {
}
