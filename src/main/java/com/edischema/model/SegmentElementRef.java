package com.edischema.model;

/**
 * A reference to an element or composite at a given position inside a segment,
 * preserving the exact sequence shown on the Stedi segment page.
 *
 * @param position  1-based element position within the segment (BEG-01 -> 1)
 * @param refId     E0353 for elements, C040 for composites
 * @param composite true if this position holds a composite
 * @param required  true when Requirement is Mandatory (Optional/Conditional -> false)
 * @param name      human readable name, used to emit XML comments
 */
public record SegmentElementRef(int position, String refId, boolean composite,
                                boolean required, String name) {
}
