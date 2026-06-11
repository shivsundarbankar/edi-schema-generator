package com.edischema.model;

import java.util.Map;

/**
 * Full result of scraping one transaction set: the structure tree plus every
 * referenced segment, composite and element definition (deduplicated).
 * Maps are insertion-ordered (LinkedHashMap) following first appearance.
 */
public record ScrapeResult(TransactionSetDoc transaction,
                           Map<String, SegmentDef> segments,
                           Map<String, CompositeDef> composites,
                           Map<String, ElementDef> elements) {
}
