package com.edischema.scraper;

import com.edischema.model.StructureNode;
import com.edischema.model.TransactionSetDoc;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    private static final Pattern LOOP_LEAD = Pattern.compile(
            "\\b([A-Z][A-Z0-9]{1,3})\\s+Loop\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LOOP_REPEAT = Pattern.compile(
            "\\bRepeat\\s*(>?)\\s*(\\d+)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern POSITION = Pattern.compile("\\b(\\d{3,4})\\b");

    private static final Pattern REQUIREMENT = Pattern.compile("\\b(Mandatory|Optional)\\b");

    private static final Pattern MAX_USE = Pattern.compile("Max\\s*(>?)\\s*(\\d+)?");

    private static final Pattern H1_TXN =
            Pattern.compile("(?i)^(?:X12\\s+)?EDI\\s+(\\d{3})\\s+(.+)$");

    public TransactionSetDoc parse(String transactionId, String releaseCode, Document document) {
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

            StructureNode.LoopUse loop = parseLoopItem(li, nestedList);
            if (loop != null) {
                nodes.add(loop);
                continue;
            }

            StructureNode.SegmentUse segment = parseSegmentItem(li);
            if (segment != null) {
                nodes.add(segment);
            } else if (nestedList != null) {
                nodes.addAll(parseList(nestedList));
            }
        }
        return nodes;
    }


    private StructureNode.LoopUse parseLoopItem(Element li, Element nestedList) {
        if (nestedList == null) {
            return null;
        }

        String text = itemTextWithoutNestedLists(li);

        Matcher leadMatcher = LOOP_LEAD.matcher(text);
        if (!leadMatcher.find()) {
            return null;
        }

        Matcher repeatMatcher = LOOP_REPEAT.matcher(text);
        if (!repeatMatcher.find()) {
            return null;
        }

        String lead = leadMatcher.group(1).toUpperCase(Locale.ROOT);

        Matcher reqMatcher = REQUIREMENT.matcher(text);
        boolean mandatory = reqMatcher.find()
                && reqMatcher.group(1).equalsIgnoreCase("Mandatory");

        int repeat = parseCount(repeatMatcher.group(1), repeatMatcher.group(2));

        String code = loopCode(lead);
        List<StructureNode> children = parseList(nestedList);

        log.debug("Loop parsed: code={} mandatory={} repeat={} text=[{}]",
                code, mandatory, repeat, text);

        return new StructureNode.LoopUse(code, lead + " Loop", mandatory, repeat, children);
    }

    private StructureNode.SegmentUse parseSegmentItem(Element li) {
        Element link = li.selectFirst("a[href*=/segment/]");
        if (link == null) {
            return null;
        }

        Matcher href = SEGMENT_HREF.matcher(link.attr("href"));
        if (!href.find()) {
            return null;
        }

        String code = href.group(1);
        String text = buildRowText(link);

        String position = "";
        Matcher pos = POSITION.matcher(text);
        if (pos.find()) {
            position = pos.group(1);
        }

        boolean mandatory = false;
        Matcher req = REQUIREMENT.matcher(text);
        if (req.find()) {
            mandatory = req.group(1).equalsIgnoreCase("Mandatory");
        }

        int maxUse = 1;
        Matcher max = MAX_USE.matcher(text);
        if (max.find()) {
            maxUse = parseCount(max.group(1), max.group(2));
        }

        String name = extractSegmentName(text, position, code);

        log.debug("Segment parsed: code={} mandatory={} maxUse={} text=[{}]",
                code, mandatory, maxUse, text);

        return new StructureNode.SegmentUse(position, code, name, mandatory, maxUse);
    }

    private static String buildRowText(Element link) {
        StringBuilder sb = new StringBuilder();
        for (Element child : link.children()) {
            String part = extractText(child);
            if (!part.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(part.trim());
            }
        }
        return normalise(sb.toString());
    }

    private static String itemTextWithoutNestedLists(Element li) {
        Element clone = li.clone();
        clone.select("ol, ul").remove();
        return normalise(extractText(clone));
    }

    private static String extractText(Element el) {
        if (el.hasClass("openInSidebar")) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        for (org.jsoup.nodes.Node node : el.childNodes()) {
            if (node instanceof org.jsoup.nodes.TextNode textNode) {
                String text = textNode.text().replace('\u00A0', ' ').trim();
                if (!text.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(text);
                }
            } else if (node instanceof Element child) {
                String childText = extractText(child);
                if (!childText.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(childText.trim());
                }
            }
        }

        return sb.toString();
    }

    private static String normalise(String text) {
        return text.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
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

    private String loopCode(String lead) {
        return lead + "_LOOP";
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
