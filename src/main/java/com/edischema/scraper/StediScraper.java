package com.edischema.scraper;

import com.edischema.config.AppProperties;
import com.edischema.model.CompositeDef;
import com.edischema.model.ElementDef;
import com.edischema.model.ReleaseUrl;
import com.edischema.model.ScrapeResult;
import com.edischema.model.SegmentDef;
import com.edischema.model.StructureNode;
import com.edischema.model.TransactionSetDoc;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates the scraping of one transaction set for any X12 release:
 *
 * <ol>
 *   <li>Fetch + parse the transaction-set page (structure with loops).</li>
 *   <li>Collect every referenced segment code in first-appearance order.</li>
 *   <li>Fetch + parse each segment page once (elements/composites inline).</li>
 *   <li>Deduplicate element and composite definitions across segments.</li>
 * </ol>
 *
 * <p>The release (4010, 5010, ...) is derived purely from the URL, so the
 * same code path works for https://www.stedi.com/edi/x12-004010/850 and
 * https://www.stedi.com/edi/x12-005010/810 alike.</p>
 */
@Component
public class StediScraper {

    private static final Logger log = LoggerFactory.getLogger(StediScraper.class);

    /** Envelope segments handled by the EDI control schema, not the transaction. */
    private static final Set<String> ENVELOPE_SEGMENTS = Set.of("ST", "SE");

    private final StediHttpClient httpClient;
    private final AppProperties props;

    public StediScraper(StediHttpClient httpClient, AppProperties props) {
        this.httpClient = httpClient;
        this.props = props;
    }

    public ScrapeResult scrape(ReleaseUrl url) {
        log.info("Scraping transaction set {} for release {}",
                url.transactionId(), url.releaseCode());

        Document txnPage = Jsoup.parse(httpClient.fetchHtml(url.transactionUrl()),
                url.transactionUrl());
        TransactionSetDoc transaction = new TransactionSetParser()
                .parse(url.transactionId(), url.releaseCode(), txnPage);

        if (props.skipEnvelopeSegments()) {
            transaction = new TransactionSetDoc(
                    transaction.id(), transaction.name(), transaction.releaseCode(),
                    stripEnvelope(transaction.heading()),
                    stripEnvelope(transaction.detail()),
                    stripEnvelope(transaction.summary()));
        }

        Set<String> segmentCodes = new LinkedHashSet<>();
        collectSegmentCodes(transaction.allAreasInOrder(), segmentCodes);
        log.info("Transaction {} references {} unique segments: {}",
                transaction.id(), segmentCodes.size(), segmentCodes);

        Map<String, SegmentDef> segments = new LinkedHashMap<>();
        Map<String, CompositeDef> composites = new LinkedHashMap<>();
        Map<String, ElementDef> elements = new LinkedHashMap<>();

        SegmentPageParser segmentParser = new SegmentPageParser();
        for (String code : segmentCodes) {
            String segmentUrl = url.segmentUrl(code);
            Document page = Jsoup.parse(httpClient.fetchHtml(segmentUrl), segmentUrl);
            SegmentPageParser.Result result = segmentParser.parse(code, page);

            segments.put(code, result.segment());
            result.composites().forEach(composites::putIfAbsent);
            result.elements().forEach(elements::putIfAbsent);
        }

        log.info("Scrape complete: {} segments, {} composites, {} elements",
                segments.size(), composites.size(), elements.size());
        return new ScrapeResult(transaction, segments, composites, elements);
    }

    private List<StructureNode> stripEnvelope(List<StructureNode> nodes) {
        return nodes.stream()
                .filter(n -> !(n instanceof StructureNode.SegmentUse s
                        && ENVELOPE_SEGMENTS.contains(s.code())))
                .map(n -> n instanceof StructureNode.LoopUse loop
                        ? new StructureNode.LoopUse(loop.code(), loop.name(),
                                loop.mandatory(), loop.repeat(), stripEnvelope(loop.children()))
                        : n)
                .toList();
    }

    private void collectSegmentCodes(List<StructureNode> nodes, Set<String> out) {
        for (StructureNode node : nodes) {
            if (node instanceof StructureNode.SegmentUse segment) {
                out.add(segment.code());
            } else if (node instanceof StructureNode.LoopUse loop) {
                collectSegmentCodes(loop.children(), out);
            }
        }
    }
}
