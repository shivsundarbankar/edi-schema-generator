package com.edischema.scraper;

import com.edischema.model.CompositeDef;
import com.edischema.model.ElementDef;
import com.edischema.model.SegmentElementRef;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentPageParserTest {

    private static SegmentPageParser.Result result;

    @BeforeAll
    static void parseFixture() throws IOException {
        try (InputStream in = SegmentPageParserTest.class
                .getResourceAsStream("/fixtures/segment-REF.html")) {
            Document html = Jsoup.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            result = new SegmentPageParser().parse("REF", html);
        }
    }

    @Test
    void segmentMetadataParsed() {
        assertEquals("REF", result.segment().code());
        assertEquals("Reference Identification", result.segment().name());
        assertEquals("To specify identifying information", result.segment().purpose());
    }

    @Test
    void elementSequenceMatchesWebsite() {
        List<SegmentElementRef> refs = result.segment().elements();
        assertEquals(4, refs.size());

        assertEquals(1, refs.get(0).position());
        assertEquals("E0128", refs.get(0).refId());
        assertFalse(refs.get(0).composite());
        assertTrue(refs.get(0).required(), "REF-01 is Mandatory");

        assertEquals("E0127", refs.get(1).refId());
        assertFalse(refs.get(1).required(), "Conditional maps to minOccurs=0");

        assertEquals("E0352", refs.get(2).refId());

        SegmentElementRef composite = refs.get(3);
        assertEquals(4, composite.position());
        assertTrue(composite.composite());
        assertEquals("C040", composite.refId());
        assertFalse(composite.required());
    }

    @Test
    void compositeComponentsParsedInOrder() {
        CompositeDef c040 = result.composites().get("C040");
        assertNotNull(c040, "C040 composite must be captured");
        assertEquals("Reference Identifier", c040.name());
        assertEquals(3, c040.components().size());

        assertEquals(1, c040.components().get(0).position());
        assertEquals("E0128", c040.components().get(0).elementRef());
        assertTrue(c040.components().get(0).required());

        assertEquals("E0127", c040.components().get(1).elementRef());
        assertTrue(c040.components().get(1).required());

        assertEquals("E0128", c040.components().get(2).elementRef());
        assertFalse(c040.components().get(2).required());
    }

    @Test
    void elementDictionaryDeduplicatedWithTypesAndLengths() {
        ElementDef e128 = result.elements().get("E0128");
        assertNotNull(e128);
        assertEquals("identifier", e128.baseType());
        assertEquals(2, e128.minLength());
        assertEquals(3, e128.maxLength());

        ElementDef e127 = result.elements().get("E0127");
        assertEquals("string", e127.baseType());
        assertEquals(30, e127.maxLength());

        // 128 appears three times on the page (REF-01, C040-01, C040-03) but once here
        assertEquals(3, result.elements().size());
    }

    @Test
    void staticHelpers() {
        assertEquals("E0353", SegmentPageParser.formatElementRef("353"));
        assertEquals("E0092", SegmentPageParser.formatElementRef("92"));
        assertEquals("E1019", SegmentPageParser.formatElementRef("1019"));
        assertEquals("identifier", SegmentPageParser.mapBaseType("ID"));
        assertEquals("string", SegmentPageParser.mapBaseType("AN"));
        assertEquals("date", SegmentPageParser.mapBaseType("DT"));
        assertEquals("time", SegmentPageParser.mapBaseType("TM"));
        assertEquals("decimal", SegmentPageParser.mapBaseType("R"));
        assertEquals("numeric", SegmentPageParser.mapBaseType("N0"));
        assertEquals("binary", SegmentPageParser.mapBaseType("B"));
    }
}
