package com.edischema.scraper;

import com.edischema.model.StructureNode;
import com.edischema.model.TransactionSetDoc;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionSetParserTest {

    private static TransactionSetDoc doc;

    @BeforeAll
    static void parseFixture() throws IOException {
        try (InputStream in = TransactionSetParserTest.class
                .getResourceAsStream("/fixtures/transaction-850.html")) {
            Document html = Jsoup.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            doc = new TransactionSetParser().parse("850", "004010", html);
        }
    }

    @Test
    void transactionNameAndReleaseAreParsed() {
        assertEquals("850", doc.id());
        assertEquals("Purchase Order", doc.name());
        assertEquals("004010", doc.releaseCode());
    }

    @Test
    void headingSequenceMatchesWebsiteOrder() {
        List<StructureNode> heading = doc.heading();
        assertEquals(5, heading.size());

        var st = assertInstanceOf(StructureNode.SegmentUse.class, heading.get(0));
        assertEquals("ST", st.code());
        assertTrue(st.mandatory());
        assertEquals(1, st.maxUse());

        var beg = assertInstanceOf(StructureNode.SegmentUse.class, heading.get(1));
        assertEquals("BEG", beg.code());
        assertEquals("Beginning Segment for Purchase Order", beg.name());

        var cur = assertInstanceOf(StructureNode.SegmentUse.class, heading.get(2));
        assertEquals("CUR", cur.code());

        var ref = assertInstanceOf(StructureNode.SegmentUse.class, heading.get(3));
        assertEquals("REF", ref.code());
        assertEquals(-1, ref.maxUse(), "Max >1 must map to the unbounded marker");

        var sacLoop = assertInstanceOf(StructureNode.LoopUse.class, heading.get(4));
        assertEquals("SAC", sacLoop.code());
        assertEquals(25, sacLoop.repeat());
        assertEquals(2, sacLoop.children().size());
    }

    @Test
    void nestedLoopsArePreserved() {
        var po1Loop = assertInstanceOf(StructureNode.LoopUse.class, doc.detail().get(0));
        assertEquals("PO1", po1Loop.code());
        assertTrue(po1Loop.mandatory());
        assertEquals(100000, po1Loop.repeat());
        assertEquals(3, po1Loop.children().size());

        // child order: PO1 segment, nested PID loop, REF segment
        assertInstanceOf(StructureNode.SegmentUse.class, po1Loop.children().get(0));
        var pidLoop = assertInstanceOf(StructureNode.LoopUse.class, po1Loop.children().get(1));
        assertEquals("PID", pidLoop.code());
        assertEquals(1000, pidLoop.repeat());
        assertEquals(1, pidLoop.children().size());

        var refInLoop = assertInstanceOf(StructureNode.SegmentUse.class,
                po1Loop.children().get(2));
        assertEquals("REF", refInLoop.code());
        assertEquals(-1, refInLoop.maxUse());
    }

    @Test
    void summaryAreaParsed() {
        var cttLoop = assertInstanceOf(StructureNode.LoopUse.class, doc.summary().get(0));
        assertEquals("CTT", cttLoop.code());
        var se = assertInstanceOf(StructureNode.SegmentUse.class, doc.summary().get(1));
        assertEquals("SE", se.code());
    }
}
