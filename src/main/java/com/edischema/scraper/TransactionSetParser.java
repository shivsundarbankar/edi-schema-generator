package com.edischema.scraper;

import com.edischema.model.StructureNode;
import com.edischema.model.TransactionSetDoc;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Stedi transaction-set page (e.g. /edi/x12-004010/850) into the
 * three structure areas (Heading / Detail / Summary), preserving the exact
 * segment and loop sequence shown on the website, including arbitrarily
 * nested loops (e.g. the PO1 loop containing PID, SAC and N1 sub-loops).
 *
 * <p>Each area on the page is an ordered list; loops are list items that
 * contain a nested ordered list. A loop item's own text follows the pattern
 * "{LEAD} Loop {Mandatory|Optional} Repeat {N|&gt;1}", while a segment item
 * contains a link to /segment/{CODE} and the pattern
 * "{POS} {CODE} {Name} {Mandatory|Optional} Max {N|&gt;1}".</p>
 */
public final class TransactionSetParser {

    private static final Logger log = LoggerFactory.getLogger(TransactionSetParser.class);

    private static final Pattern SEGMENT_HREF =
            Pattern.compile("/segment/([A-Z][A-Z0-9]{1,2})/?(?:[?#].*)?$");

    private static final Pattern LOOP_TEXT = Pattern.compile(
            "([A-Z][A-Z0-9]{1,3})\\s+Loop\\b.*?(Mandatory|Optional)?\\b.*?Repeat\\s*(>?)\\s*(\\d+)?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern POSITION = Pattern.compile("\\b(\\d{3,4})\\b");

    private static final Pattern REQUIREMENT = Pattern.compile("\\b(Mandatory|Optional)\\b");

    private static final Pattern MAX_USE = Pattern.compile("Max\\s*(>?)\\s*(\\d+)?");

    private static final Pattern H1_TXN =
            Pattern.compile("(?i)^(?:X12\\s+)?EDI\\s+(\\d{3})\\s+(.+)$");

    /** Counter used to keep generated loop codes unique within one transaction. */
    private final Map<String, Integer> loopCodeCounts = new HashMap<>();

    public TransactionSetDoc parse(String transactionId, String releaseCode, Document document) {
        loopCodeCounts.clear();

        String name = extractTransactionName(transactionId, document);

        List<StructureNode> heading = parseArea(document, "heading");
        List<StructureNode> detail = parseArea(document, "detail");
        List<StructureNode> summary = parseArea(document, "summary");

        if (heading.isEmpty() && detail.isEmpty() && summary.isEmpty()) {
            throw new ScrapeException("Could not locate Heading/Detail/Summary structure on "
                    + "transaction page " + transactionId + ". The website layout may have "
                    + "changed - inspect the cached HTML and adjust TransactionSetParser.");
        }

        log.info("Parsed transaction {} '{}' (heading={}, detail={}, summary={})",
                transactionId, name, count(heading), count(detail), count(summary));
        return new TransactionSetDoc(transactionId, name, releaseCode, heading, detail, summary);
    }

    private List<StructureNode> parseArea(Document document, String areaKey) {
        Element header = findAreaHeader(document, areaKey);
        if (header == null) {
            return List.of();
        }
        Element list = findFollowingStructureList(document, header);
        if (list == null) {
            return List.of();
        }
        return parseList(list);
    }

    private Element findAreaHeader(Document document, String areaKey) {
        // Prefer elements whose id matches (the page uses anchors #heading etc.)
        Element byId = document.getElementById(areaKey);
        if (byId != null) {
            return byId;
        }
        for (Element h : document.select("h1, h2, h3")) {
            if (h.text().trim().toLowerCase(Locale.ROOT).equals(areaKey)) {
                return h;
            }
            Element anchor = h.selectFirst("a[href=#" + areaKey + "]");
            if (anchor != null) {
                return h;
            }
        }
        return null;
    }

    /**
     * Walks forward in document order from the area header and returns the
     * first outermost ordered/unordered list that contains segment links.
     */
    private Element findFollowingStructureList(Document document, Element header) {
        List<Element> all = document.getAllElements();
        int start = all.indexOf(header);
        if (start < 0) {
            return null;
        }
        for (int i = start + 1; i < all.size(); i++) {
            Element el = all.get(i);
            String tag = el.tagName();
            if ((tag.equals("ol") || tag.equals("ul"))
                    && el.selectFirst("a[href*=/segment/]") != null) {
                // climb to the outermost list so nested loop lists are handled recursively
                Element outer = el;
                Element parent = outer.parent();
                while (parent != null) {
                    if (parent.tagName().equals("ol") || parent.tagName().equals("ul")) {
                        outer = parent;
                    }
                    if (parent == header.parent()) {
                        break;
                    }
                    parent = parent.parent();
                }
                return outer;
            }
            // stop when we run into the next area header
            if ((tag.equals("h1") || tag.equals("h2")) && el != header
                    && el.text().trim().toLowerCase(Locale.ROOT)
                    .matches("heading|detail|summary")) {
                return null;
            }
        }
        return null;
    }

    private List<StructureNode> parseList(Element list) {
        List<StructureNode> nodes = new ArrayList<>();
        for (Element li : list.children()) {
            if (!li.tagName().equals("li")) {
                continue;
            }
            Element nestedList = firstNestedList(li);
            String ownText = ownText(li);

            Matcher loop = LOOP_TEXT.matcher(ownText);
            if (nestedList != null && loop.find()) {
                String lead = loop.group(1).toUpperCase(Locale.ROOT);
                boolean mandatory = "Mandatory".equalsIgnoreCase(loop.group(2));
                int repeat = parseCount(loop.group(3), loop.group(4));
                String code = uniqueLoopCode(lead);
                List<StructureNode> children = parseList(nestedList);
                nodes.add(new StructureNode.LoopUse(code, lead + " Loop", mandatory,
                        repeat, children));
                continue;
            }

            StructureNode.SegmentUse segment = parseSegmentItem(li, ownText);
            if (segment != null) {
                nodes.add(segment);
            } else if (nestedList != null) {
                // Defensive: a nested list without a recognizable loop label -
                // keep its children inline so no segment is silently dropped.
                nodes.addAll(parseList(nestedList));
            }
        }
        return nodes;
    }

    private StructureNode.SegmentUse parseSegmentItem(Element li, String ownText) {
        Element link = li.selectFirst("a[href*=/segment/]");
        if (link == null) {
            return null;
        }

        Matcher href = SEGMENT_HREF.matcher(link.attr("href"));
        if (!href.find()) {
            return null;
        }
        String code = href.group(1);

        String rowText = buildRowText(link);
        log.debug("Segment {} rowText=[{}]", code, rowText);

        String position = "";
        Matcher pos = POSITION.matcher(rowText);
        if (pos.find()) {
            position = pos.group(1);
        }

        boolean mandatory = false;
        Matcher req = REQUIREMENT.matcher(rowText);
        if (req.find()) {
            mandatory = req.group(1).equalsIgnoreCase("Mandatory");
            log.debug("Segment {} mandatory={} matched=[{}]", code, mandatory, req.group(1));
        } else {
            log.warn("Segment {} - no Mandatory/Optional found in rowText=[{}]", code, rowText);
        }

        int maxUse = 1;
        Matcher max = MAX_USE.matcher(rowText);
        if (max.find()) {
            maxUse = parseCount(max.group(1), max.group(2));
        }

        String name = extractSegmentName(rowText, position, code);
        return new StructureNode.SegmentUse(position, code, name, mandatory, maxUse);
    }

    /**
     * Builds row text by walking each span inside the <a> tag and joining
     * their individual text content with spaces - prevents Jsoup from
     * concatenating adjacent span texts without spaces.
     *
     * Example spans:
     *   <span>010</span>           -> "010"
     *   <span><button>BEG</button></span> -> "BEG"
     *   <span>Transaction Set Header<span>Mandatory</span><span>-></span></span> -> "Transaction Set Header Mandatory ->"
     *   <span>Max 1</span>        -> "Max 1"
     *
     * Result: "010 BEG Transaction Set Header Mandatory -> Max 1"
     */
    private static String buildRowText(Element link) {
        // Get the top-level spans inside the <a>
        StringBuilder sb = new StringBuilder();
        for (Element span : link.children()) {
            String spanText = spanToText(span);
            if (!spanText.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(spanText.trim());
            }
        }
        return sb.toString()
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Recursively extracts text from a span, joining child texts with spaces.
     * Skips the "->" / "openInSidebar" arrow span as it's decorative.
     */
    private static String spanToText(Element el) {
        if (el.hasClass("openInSidebar")) {
            return "";
        }

        if (el.children().isEmpty()) {
            return el.text();
        }

        StringBuilder sb = new StringBuilder();
        String ownText = el.ownText();
        if (!ownText.isBlank()) {
            sb.append(ownText.trim());
        }
        for (Element child : el.children()) {
            String childText = spanToText(child);
            if (!childText.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(childText.trim());
            }
        }
        return sb.toString();
    }

    /** "010 ST Transaction Set Header Mandatory Max 1 ..." -> "Transaction Set Header" */
    private String extractSegmentName(String ownText, String position, String code) {
        String text = ownText;
        if (!position.isEmpty()) {
            int idx = text.indexOf(position);
            if (idx >= 0) {
                text = text.substring(idx + position.length());
            }
        }
        text = text.trim();
        if (text.startsWith(code)) {
            text = text.substring(code.length()).trim();
        }
        Matcher req = REQUIREMENT.matcher(text);
        if (req.find()) {
            text = text.substring(0, req.start()).trim();
        }
        return text;
    }

    /** ">" + null -> -1 (unbounded); otherwise the numeric value (default 1). */
    private static int parseCount(String gt, String number) {
        if (gt != null && !gt.isEmpty()) {
            return -1;
        }
        return number != null ? Integer.parseInt(number) : 1;
    }

    private String uniqueLoopCode(String lead) {
        int count = loopCodeCounts.merge(lead, 1, Integer::sum);
        return count == 1 ? lead : lead + "_" + count;
    }

    /** Text of the list item excluding any nested lists. */
    private static String ownText(Element li) {
        Element clone = li.clone();
        clone.select("ol, ul").remove();
        // Use wholeText approach - join each child's text with spaces
        StringBuilder sb = new StringBuilder();
        for (Element child : clone.children()) {
            String t = child.text().replace('\u00A0', ' ').trim();
            if (!t.isBlank()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(t);
            }
        }
        // Fallback to direct text if no children
        String result = sb.length() > 0 ? sb.toString() : clone.text();
        return result.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static Element firstNestedList(Element li) {
        return li.selectFirst("ol, ul");
    }

    private String extractTransactionName(String transactionId, Document document) {
        Element h1 = document.selectFirst("h1");
        if (h1 != null) {
            Matcher m = H1_TXN.matcher(h1.text().trim());
            if (m.matches()) {
                return m.group(2).trim();
            }
            return h1.text().trim();
        }
        return "Transaction Set " + transactionId;
    }

    private static int count(List<StructureNode> nodes) {
        int total = 0;
        for (StructureNode n : nodes) {
            if (n instanceof StructureNode.LoopUse loop) {
                total += 1 + count(loop.children());
            } else {
                total++;
            }
        }
        return total;
    }
}
