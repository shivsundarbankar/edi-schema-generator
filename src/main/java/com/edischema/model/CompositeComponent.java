package com.edischema.model;

/**
 * One component element inside a composite, in sequence order.
 *
 * @param position   1-based position within the composite
 * @param elementRef e.g. E0128
 * @param required   true when Stedi marks the component Mandatory
 * @param name       component element name (for comments)
 */
public record CompositeComponent(int position, String elementRef, boolean required, String name) {
}
