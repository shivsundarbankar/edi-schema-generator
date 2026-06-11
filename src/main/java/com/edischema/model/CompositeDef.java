package com.edischema.model;

import java.util.List;

/**
 * A composite data element definition (e.g. C040 Reference Identifier).
 */
public record CompositeDef(String ref, String name, List<CompositeComponent> components) {
}
