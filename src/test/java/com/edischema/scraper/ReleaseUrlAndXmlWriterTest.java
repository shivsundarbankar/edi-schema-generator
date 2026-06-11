package com.edischema.scraper;

import com.edischema.config.AppProperties;
import com.edischema.model.CompositeComponent;
import com.edischema.model.CompositeDef;
import com.edischema.model.ElementDef;
import com.edischema.model.ReleaseUrl;
import com.edischema.model.ScrapeResult;
import com.edischema.model.SegmentDef;
import com.edischema.model.SegmentElementRef;
import com.edischema.model.StructureNode;
import com.edischema.model.TransactionSetDoc;
import com.edischema.xml.EdiSchemaXmlWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseUrlAndXmlWriterTest {

    @Test
    void parsesAnyReleaseFromUrl() {
        ReleaseUrl u4010 = ReleaseUrl.parse("https://www.stedi.com/edi/x12-004010/850");
        assertEquals("004010", u4010.releaseCode());
        assertEquals("850", u4010.transactionId());
        assertEquals("https://www.stedi.com/edi/x12-004010/segment/BEG",
                u4010.segmentUrl("BEG"));

        ReleaseUrl u5010 = ReleaseUrl.parse("https://www.stedi.com/edi/x12-005010/810/");
        assertEquals("005010", u5010.releaseCode());
        assertEquals("810", u5010.transactionId());

        assertThrows(IllegalArgumentException.class,
                () -> ReleaseUrl.parse("https://www.stedi.com/edi/850"));
    }

    @Test
    void xmlOutputMatchesReferenceFormat(@TempDir Path tempDir) throws IOException {
        AppProperties props = new AppProperties("test-agent", 0, 10, 1, 0,
                tempDir.resolve("cache").toString(), 1,
                tempDir.toString(), 99999, true);

        ScrapeResult result = sampleResult();
        EdiSchemaXmlWriter writer = new EdiSchemaXmlWriter(props);
        List<Path> files = writer.write(result, tempDir, false);
        assertEquals(3, files.size());

        String common = Files.readString(tempDir.resolve("common-elements.xml"));
        assertTrue(common.contains("<schema xmlns=\"http://xlate.io/EDISchema/v4\">"));
        assertTrue(common.contains(
                "<elementType name=\"E0353\" base=\"identifier\" minLength=\"2\" maxLength=\"2\" />"));
        assertTrue(common.contains("<compositeType name=\"C040\">"));
        assertTrue(common.contains("<element type=\"E0128\" minOccurs=\"1\"/>"));

        String segments = Files.readString(tempDir.resolve("850-segments.xml"));
        assertTrue(segments.contains("<include schemaLocation=\"common-elements.xml\"/>"));
        assertTrue(segments.contains("<segmentType name=\"REF\">"));
        assertTrue(segments.contains("<composite type=\"C040\" minOccurs=\"0\"/>"));
        assertTrue(segments.contains("<!-- BEG01: Transaction Set Purpose Code -->"));

        String txn = Files.readString(tempDir.resolve("850.xml"));
        assertTrue(txn.contains("<include schemaLocation=\"850-segments.xml\"/>"));
        assertTrue(txn.contains("<segment type=\"BEG\" minOccurs=\"1\" maxOccurs=\"1\"/>"));
        assertTrue(txn.contains("<loop code=\"N1\" minOccurs=\"0\" maxOccurs=\"200\">"));
        // "Max >1" becomes the configured unbounded substitute
        assertTrue(txn.contains("<segment type=\"REF\" minOccurs=\"0\" maxOccurs=\"99999\"/>"));
        // loop body order: N1 then REF, exactly as in the structure
        int loopIdx = txn.indexOf("<loop code=\"N1\"");
        int n1Idx = txn.indexOf("<segment type=\"N1\"");
        int refInLoopIdx = txn.indexOf("<segment type=\"REF\" minOccurs=\"0\"", loopIdx);
        assertTrue(loopIdx < n1Idx && n1Idx < refInLoopIdx,
                "sequence inside loop must match source order");
    }

    private static ScrapeResult sampleResult() {
        Map<String, ElementDef> elements = new LinkedHashMap<>();
        elements.put("E0353", new ElementDef("E0353", "353",
                "Transaction Set Purpose Code", "identifier", 2, 2));
        elements.put("E0128", new ElementDef("E0128", "128",
                "Reference Identification Qualifier", "identifier", 2, 3));
        elements.put("E0127", new ElementDef("E0127", "127",
                "Reference Identification", "string", 1, 30));

        Map<String, CompositeDef> composites = new LinkedHashMap<>();
        composites.put("C040", new CompositeDef("C040", "Reference Identifier", List.of(
                new CompositeComponent(1, "E0128", true, "Reference Identification Qualifier"),
                new CompositeComponent(2, "E0127", true, "Reference Identification"))));

        Map<String, SegmentDef> segments = new LinkedHashMap<>();
        segments.put("BEG", new SegmentDef("BEG", "Beginning Segment for Purchase Order",
                "To indicate the beginning", List.of(
                new SegmentElementRef(1, "E0353", false, true,
                        "Transaction Set Purpose Code"))));
        segments.put("N1", new SegmentDef("N1", "Name", "To identify a party", List.of(
                new SegmentElementRef(1, "E0128", false, true, "Entity Identifier Code"))));
        segments.put("REF", new SegmentDef("REF", "Reference Identification",
                "To specify identifying information", List.of(
                new SegmentElementRef(1, "E0128", false, true,
                        "Reference Identification Qualifier"),
                new SegmentElementRef(4, "C040", true, false, "Reference Identifier"))));

        TransactionSetDoc doc = new TransactionSetDoc("850", "Purchase Order", "004010",
                List.of(
                        new StructureNode.SegmentUse("020", "BEG",
                                "Beginning Segment for Purchase Order", true, 1),
                        new StructureNode.SegmentUse("050", "REF",
                                "Reference Identification", false, -1),
                        new StructureNode.LoopUse("N1", "N1 Loop", false, 200, List.of(
                                new StructureNode.SegmentUse("310", "N1", "Name", true, 1),
                                new StructureNode.SegmentUse("330", "REF",
                                        "Reference Identification", false, -1)))),
                List.of(),
                List.of());
        return new ScrapeResult(doc, segments, composites, elements);
    }
}
