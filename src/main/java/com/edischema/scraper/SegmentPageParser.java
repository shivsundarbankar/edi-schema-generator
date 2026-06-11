package com.edischema.scraper;

import com.edischema.model.CompositeComponent;
import com.edischema.model.CompositeDef;
import com.edischema.model.ElementDef;
import com.edischema.model.SegmentDef;
import com.edischema.model.SegmentElementRef;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Stedi segment page (e.g. /edi/x12-004010/segment/BEG) into a
 * {@link SegmentDef} plus every element and composite definition found on it.
 *
 * <p>The parser works on the linearized token stream of the page (see
 * {@link PageTokenizer}) rather than CSS selectors, so it is resilient to
 * styling/markup changes. The reading order of a segment page is stable:</p>
 *
 * <pre>
 *   BEG-01  | 353 (link) | Transaction Set Purpose Code | Identifier (ID) | Mandatory | 2 | 2 | -
 *   ...
 *   REF-04  | C040 (link) | Reference Identifier | Composite (composite) | Optional
 *      01   | 128 (link) | Reference Identification Qualifier | Identifier (ID) | Mandatory | 2 | 3 | -
 *      02   | 127 (link) | ...
 * </pre>
 *
 * <p>Element/composite sequence is preserved exactly as displayed.</p>
 */
public final class SegmentPageParser {

    private static final Logger log = LoggerFactory.getLogger(SegmentPageParser.class);

    /** href ending in /element/{id} where id is e.g. 353, 1019 or C040. */
    private static final Pattern ELEMENT_HREF =
            Pattern.compile("/element/([A-Z]*\\d+)/?(?:[?#].*)?$");

    /** Segment-level position marker, e.g. BEG-01, REF-04, PO1-12. */
    private static final Pattern SEGMENT_POSITION =
            Pattern.compile("^([A-Z][A-Z0-9]{1,2})-(\\d{2})$");

    /** Composite component position marker, e.g. 01, 02. */
    private static final Pattern COMPONENT_POSITION = Pattern.compile("^(\\d{2})$");

    /** Simple element type marker, e.g. "Identifier (ID)", "Numeric (N0)". */
    private static final Pattern SIMPLE_TYPE =
            Pattern.compile("\\((ID|AN|DT|TM|B|R|N\\d?)\\)");

    private static final Pattern COMPOSITE_TYPE =
            Pattern.compile("(?i)composite\\s*\\(composite\\)");

    private static final Pattern REQUIREMENT =
            Pattern.compile("^(Mandatory|Optional|Conditional|Relational)$");

    private static final Pattern INTEGER = Pattern.compile("^\\d+$");

    /** Result of parsing a single segment page. */
    public record Result(SegmentDef segment,
                         Map<String, CompositeDef> composites,
                         Map<String, ElementDef> elements) {
    }

    private PageTokenizer.Token token(List<PageTokenizer.Token> tokens, int index) {
        return index >= 0 && index < tokens.size() ? tokens.get(index) : null;
    }

    public Result parse(String segmentCode, Document document) {
        List<PageTokenizer.Token> tokens = PageTokenizer.tokenize(document);

        String segmentName = extractSegmentName(segmentCode, document);
        String purpose = extractPurpose(document);

        List<SegmentElementRef> segmentRefs = new ArrayList<>();
        Map<String, CompositeDef> composites = new LinkedHashMap<>();
        Map<String, ElementDef> elements = new LinkedHashMap<>();

        // Mutable composite under construction
        String activeCompositeRef = null;
        String activeCompositeName = null;
        List<CompositeComponent> activeComponents = null;

        int pendingSegmentPosition = -1;
        int pendingComponentPosition = -1;
        int segmentAutoPosition = 0;
        int componentAutoPosition = 0;

        for (int i = 0; i < tokens.size(); i++) {
            PageTokenizer.Token t = tokens.get(i);

            Matcher segPos = SEGMENT_POSITION.matcher(t.text());
            if (segPos.matches() && segPos.group(1).equals(segmentCode)) {
                // A new segment-level row begins -> any open composite is finished.
                if (activeCompositeRef != null) {
                    composites.putIfAbsent(activeCompositeRef,
                            new CompositeDef(activeCompositeRef, activeCompositeName,
                                    List.copyOf(activeComponents)));
                    activeCompositeRef = null;
                    activeComponents = null;
                }
                pendingSegmentPosition = Integer.parseInt(segPos.group(2));
                pendingComponentPosition = -1;
                continue;
            }

            if (activeCompositeRef != null) {
                Matcher compPos = COMPONENT_POSITION.matcher(t.text());
                if (compPos.matches() && !t.isLink()
                        || compPos.matches() && t.isLink() && t.href().startsWith("#")) {
                    pendingComponentPosition = Integer.parseInt(compPos.group(1));
                    continue;
                }
                // Anchor links such as "01" pointing to "#C040-01"
                if (t.isLink() && t.href() != null && t.href().contains("#")
                        && COMPONENT_POSITION.matcher(t.text()).matches()) {
                    pendingComponentPosition = Integer.parseInt(t.text());
                    continue;
                }
            }

            if (!t.isLink() || t.href() == null) {
                continue;
            }
            Matcher href = ELEMENT_HREF.matcher(t.href());
            if (!href.find()) {
                continue;
            }
            String rawId = href.group(1);

            RowDetails details = readRowDetails(tokens, i + 1);
            if (details == null) {
                continue; // not a definition row (e.g. a stray reference link)
            }

            boolean isComposite = rawId.startsWith("C") || details.composite();
            if (isComposite) {
                int position = pendingSegmentPosition > 0
                        ? pendingSegmentPosition : ++segmentAutoPosition;
                segmentAutoPosition = Math.max(segmentAutoPosition, position);
                pendingSegmentPosition = -1;

                segmentRefs.add(new SegmentElementRef(position, rawId, true,
                        details.mandatory(), details.name()));

                activeCompositeRef = rawId;
                activeCompositeName = details.name();
                activeComponents = new ArrayList<>();
                componentAutoPosition = 0;
                continue;
            }

            String elementRef = formatElementRef(rawId);
            ElementDef def = new ElementDef(elementRef, rawId, details.name(),
                    mapBaseType(details.typeCode()), details.min(), details.max());
            mergeElement(elements, def);

            if (activeCompositeRef != null && (pendingComponentPosition > 0
                    || looksLikeComponentRow(tokens, i))) {
                int position = pendingComponentPosition > 0
                        ? pendingComponentPosition : ++componentAutoPosition;
                componentAutoPosition = Math.max(componentAutoPosition, position);
                pendingComponentPosition = -1;
                activeComponents.add(new CompositeComponent(position, elementRef,
                        details.mandatory(), details.name()));
            } else {
                // New segment-level simple element row closes any open composite.
                if (activeCompositeRef != null) {
                    composites.putIfAbsent(activeCompositeRef,
                            new CompositeDef(activeCompositeRef, activeCompositeName,
                                    List.copyOf(activeComponents)));
                    activeCompositeRef = null;
                    activeComponents = null;
                }
                int position = pendingSegmentPosition > 0
                        ? pendingSegmentPosition : ++segmentAutoPosition;
                segmentAutoPosition = Math.max(segmentAutoPosition, position);
                pendingSegmentPosition = -1;
                segmentRefs.add(new SegmentElementRef(position, elementRef, false,
                        details.mandatory(), details.name()));
            }
        }

        if (activeCompositeRef != null) {
            composites.putIfAbsent(activeCompositeRef,
                    new CompositeDef(activeCompositeRef, activeCompositeName,
                            List.copyOf(activeComponents)));
        }

        if (segmentRefs.isEmpty()) {
            throw new ScrapeException("Could not find any element rows on segment page for '"
                    + segmentCode + "'. The website layout may have changed - inspect the "
                    + "cached HTML in the cache directory and adjust SegmentPageParser.");
        }

        segmentRefs.sort(java.util.Comparator.comparingInt(SegmentElementRef::position));
        SegmentDef segment = new SegmentDef(segmentCode, segmentName, purpose,
                List.copyOf(segmentRefs));
        log.info("Parsed segment {} ({} positions, {} composites)",
                segmentCode, segmentRefs.size(), composites.size());
        return new Result(segment, composites, elements);
    }

    /**
     * Reads the tokens following an element link: name, type, requirement and,
     * for simple elements, min/max lengths. Returns null when the window does
     * not look like a definition row.
     */
    private RowDetails readRowDetails(List<PageTokenizer.Token> tokens, int from) {
        String name = null;
        String typeCode = null;
        boolean composite = false;
        Boolean mandatory = null;
        Integer min = null;
        Integer max = null;

        int window = Math.min(tokens.size(), from + 10);
        for (int i = from; i < window; i++) {
            PageTokenizer.Token t = tokens.get(i);
            String text = t.text();

            if (typeCode == null && !composite) {
                Matcher simple = SIMPLE_TYPE.matcher(text);
                if (simple.find()) {
                    typeCode = simple.group(1);
                    continue;
                }
                if (COMPOSITE_TYPE.matcher(text).find()) {
                    composite = true;
                    continue;
                }
            }

            if (mandatory == null && REQUIREMENT.matcher(text).matches()) {
                mandatory = text.equals("Mandatory");
                if (composite) {
                    break; // composite header rows carry no min/max
                }
                continue;
            }

            if (mandatory != null && INTEGER.matcher(text).matches()) {
                if (min == null) {
                    min = Integer.parseInt(text);
                } else if (max == null) {
                    max = Integer.parseInt(text);
                    break;
                }
                continue;
            }

            if (name == null && typeCode == null && !composite && !t.isLink()
                    && text.length() > 1 && !INTEGER.matcher(text).matches()) {
                name = text;
            }
        }

        if (composite) {
            return new RowDetails(name == null ? "" : name, null, true,
                    mandatory != null && mandatory, 0, 0);
        }
        if (typeCode == null || mandatory == null) {
            return null; // not a definition row
        }
        return new RowDetails(name == null ? "" : name, typeCode, false, mandatory,
                min == null ? 1 : min, max == null ? (min == null ? 1 : min) : max);
    }

    /**
     * Fallback heuristic: a row belongs to an open composite when the nearest
     * preceding position marker was a two-digit component marker.
     */
    private boolean looksLikeComponentRow(List<PageTokenizer.Token> tokens, int linkIndex) {
        for (int i = linkIndex - 1; i >= Math.max(0, linkIndex - 4); i--) {
            PageTokenizer.Token t = token(tokens, i);
            if (t == null) {
                break;
            }
            if (COMPONENT_POSITION.matcher(t.text()).matches()) {
                return true;
            }
            if (SEGMENT_POSITION.matcher(t.text()).matches()) {
                return false;
            }
        }
        return false;
    }

    private record RowDetails(String name, String typeCode, boolean composite,
                              boolean mandatory, int min, int max) {
    }

    private void mergeElement(Map<String, ElementDef> elements, ElementDef candidate) {
        ElementDef existing = elements.get(candidate.ref());
        if (existing == null) {
            elements.put(candidate.ref(), candidate);
            return;
        }
        if (existing.minLength() != candidate.minLength()
                || existing.maxLength() != candidate.maxLength()
                || !existing.baseType().equals(candidate.baseType())) {
            log.warn("Element {} appears with differing attributes ({} vs {}); keeping first",
                    candidate.ref(), existing, candidate);
        }
    }

    private String extractSegmentName(String segmentCode, Document document) {
        var h1 = document.selectFirst("h1");
        if (h1 != null) {
            String text = h1.text().trim()
                    .replaceFirst("(?i)^X12\\s+EDI\\s+", "")
                    .replaceFirst("(?i)^EDI\\s+", "");
            if (text.startsWith(segmentCode + " ")) {
                return text.substring(segmentCode.length() + 1).trim();
            }
            if (!text.isEmpty()) {
                return text;
            }
        }
        return segmentCode;
    }

    private String extractPurpose(Document document) {
        var h1 = document.selectFirst("h1");
        if (h1 != null) {
            var next = h1.nextElementSibling();
            while (next != null) {
                if (next.tagName().matches("h2|h3|p")) {
                    String text = next.text().trim();
                    if (!text.isEmpty()) {
                        return text;
                    }
                }
                next = next.nextElementSibling();
            }
        }
        return "";
    }

    /** 353 -> E0353, 1019 -> E1019 */
    public static String formatElementRef(String rawNumber) {
        String digits = rawNumber.replaceAll("\\D", "");
        return "E" + "0".repeat(Math.max(0, 4 - digits.length())) + digits;
    }

    /** Maps Stedi/X12 type codes to EDISchema v4 base types. */
    public static String mapBaseType(String typeCode) {
        if (typeCode == null) {
            return "string";
        }
        return switch (typeCode) {
            case "ID" -> "identifier";
            case "AN" -> "string";
            case "DT" -> "date";
            case "TM" -> "time";
            case "R" -> "decimal";
            case "B" -> "binary";
            default -> typeCode.startsWith("N") ? "numeric" : "string";
        };
    }
}
