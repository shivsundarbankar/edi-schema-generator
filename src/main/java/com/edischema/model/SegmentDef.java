package com.edischema.model;

import java.util.List;

/**
 * A segment type definition with its ordered element/composite references.
 */
public record SegmentDef(String code, String name, String purpose,
                         List<SegmentElementRef> elements) {
}
