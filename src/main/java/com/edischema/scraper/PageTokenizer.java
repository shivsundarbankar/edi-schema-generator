package com.edischema.scraper;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Linearizes a Jsoup document into an ordered token stream of text fragments
 * and links. This makes the segment-page parser independent of CSS classes
 * and the exact tag structure Stedi uses - it only relies on the visible
 * reading order of the page, which is stable.
 */
public final class PageTokenizer {

    /** A single token: visible text, plus the href when it came from a link. */
    public record Token(String text, String href) {
        public boolean isLink() {
            return href != null;
        }
    }

    private static final Set<String> SKIPPED_TAGS =
            Set.of("script", "style", "noscript", "nav", "header", "footer", "svg", "form");

    private PageTokenizer() {
    }

    public static List<Token> tokenize(Document document) {
        List<Token> tokens = new ArrayList<>();
        Element body = document.body();
        if (body != null) {
            walk(body, tokens);
        }
        return tokens;
    }

    private static void walk(Node node, List<Token> tokens) {
        if (node instanceof Element el) {
            String tag = el.tagName().toLowerCase();
            if (SKIPPED_TAGS.contains(tag)) {
                return;
            }
            if (tag.equals("a")) {
                String text = el.text().trim();
                String href = el.attr("href").trim();
                if (!text.isEmpty()) {
                    tokens.add(new Token(text, href.isEmpty() ? null : href));
                }
                return; // do not descend further into anchors
            }
            for (Node child : el.childNodes()) {
                walk(child, tokens);
            }
        } else if (node instanceof TextNode textNode) {
            String text = textNode.text().trim();
            if (!text.isEmpty()) {
                tokens.add(new Token(text, null));
            }
        }
    }
}
