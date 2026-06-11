package com.edischema.xml;

import com.edischema.config.AppProperties;
import com.edischema.model.CompositeComponent;
import com.edischema.model.CompositeDef;
import com.edischema.model.ElementDef;
import com.edischema.model.ScrapeResult;
import com.edischema.model.SegmentDef;
import com.edischema.model.SegmentElementRef;
import com.edischema.model.StructureNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes EDISchema v4 XML (namespace http://xlate.io/EDISchema/v4) in the
 * same layout as the manually-maintained reference files:
 *
 * <ul>
 *   <li>{@code common-elements.xml} - elementType + compositeType definitions</li>
 *   <li>{@code {txn}-segments.xml}  - segmentType definitions, includes common-elements</li>
 *   <li>{@code {txn}.xml}           - transaction structure, includes {txn}-segments</li>
 * </ul>
 *
 * <p>In combined mode everything is emitted into a single self-contained file
 * {@code {txn}.xml} with no includes.</p>
 */
@Component
public class EdiSchemaXmlWriter {

    private static final Logger log = LoggerFactory.getLogger(EdiSchemaXmlWriter.class);
    private static final String NS = "http://xlate.io/EDISchema/v4";
    private static final String INDENT = "    ";

    private final AppProperties props;

    public EdiSchemaXmlWriter(AppProperties props) {
        this.props = props;
    }

    /** Writes the schema files and returns the list of created paths. */
    public List<Path> write(ScrapeResult result, Path outputDir, boolean combined)
            throws IOException {
        Files.createDirectories(outputDir);
        List<Path> written = new ArrayList<>();
        String txn = result.transaction().id();

        if (combined) {
            Path file = outputDir.resolve(txn + ".xml");
            StringBuilder sb = header();
            appendElementTypes(sb, result, 1);
            sb.append('\n');
            appendCompositeTypes(sb, result, 1);
            sb.append('\n');
            appendSegmentTypes(sb, result, 1);
            sb.append('\n');
            appendTransaction(sb, result, 1);
            footer(sb);
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            written.add(file);
            log.info("Wrote {}", file);
            return written;
        }

        // common-elements.xml
        Path commonFile = outputDir.resolve("common-elements.xml");
        StringBuilder common = header();
        appendElementTypes(common, result, 1);
        sbNewlineIfComposites(common, result);
        appendCompositeTypes(common, result, 1);
        footer(common);
        Files.writeString(commonFile, common.toString(), StandardCharsets.UTF_8);
        written.add(commonFile);

        // {txn}-segments.xml
        Path segmentsFile = outputDir.resolve(txn + "-segments.xml");
        StringBuilder segments = header();
        segments.append(INDENT).append("<include schemaLocation=\"common-elements.xml\"/>\n\n");
        appendSegmentTypes(segments, result, 1);
        footer(segments);
        Files.writeString(segmentsFile, segments.toString(), StandardCharsets.UTF_8);
        written.add(segmentsFile);

        // {txn}.xml
        Path txnFile = outputDir.resolve(txn + ".xml");
        StringBuilder transaction = header();
        transaction.append(INDENT).append("<include schemaLocation=\"")
                .append(txn).append("-segments.xml\"/>\n\n");
        appendTransaction(transaction, result, 1);
        footer(transaction);
        Files.writeString(txnFile, transaction.toString(), StandardCharsets.UTF_8);
        written.add(txnFile);

        written.forEach(p -> log.info("Wrote {}", p));
        return written;
    }

    private static StringBuilder header() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<schema xmlns=\"").append(NS).append("\">\n\n");
        return sb;
    }

    private static void footer(StringBuilder sb) {
        sb.append("\n</schema>\n");
    }

    private static void sbNewlineIfComposites(StringBuilder sb, ScrapeResult result) {
        if (!result.composites().isEmpty()) {
            sb.append('\n');
        }
    }

    // ------------------------------------------------------------------
    // elementType definitions
    // ------------------------------------------------------------------
    private void appendElementTypes(StringBuilder sb, ScrapeResult result, int depth) {
        String pad = INDENT.repeat(depth);
        for (ElementDef e : result.elements().values()) {
            sb.append(pad)
              .append("<elementType name=\"").append(e.ref())
              .append("\" base=\"").append(e.baseType())
              .append("\" minLength=\"").append(e.minLength())
              .append("\" maxLength=\"").append(e.maxLength())
              .append("\" />");
            if (!e.name().isBlank()) {
                sb.append(" <!-- ").append(comment(e.name())).append(" -->");
            }
            sb.append('\n');
        }
    }

    // ------------------------------------------------------------------
    // compositeType definitions
    // ------------------------------------------------------------------
    private void appendCompositeTypes(StringBuilder sb, ScrapeResult result, int depth) {
        String pad = INDENT.repeat(depth);
        String pad2 = INDENT.repeat(depth + 1);
        String pad3 = INDENT.repeat(depth + 2);
        for (CompositeDef c : result.composites().values()) {
            sb.append(pad).append("<compositeType name=\"").append(c.ref()).append("\">");
            if (!c.name().isBlank()) {
                sb.append(" <!-- ").append(comment(c.name())).append(" -->");
            }
            sb.append('\n');
            sb.append(pad2).append("<sequence>\n");
            for (CompositeComponent comp : c.components()) {
                sb.append(pad3)
                  .append("<element type=\"").append(comp.elementRef())
                  .append("\" minOccurs=\"").append(comp.required() ? 1 : 0)
                  .append("\"/>")
                  .append("<!-- ").append(c.ref()).append(String.format("%02d", comp.position()))
                  .append(": ").append(comment(comp.name())).append(" -->\n");
            }
            sb.append(pad2).append("</sequence>\n");
            sb.append(pad).append("</compositeType>\n\n");
        }
    }

    // ------------------------------------------------------------------
    // segmentType definitions
    // ------------------------------------------------------------------
    private void appendSegmentTypes(StringBuilder sb, ScrapeResult result, int depth) {
        String pad = INDENT.repeat(depth);
        String pad2 = INDENT.repeat(depth + 1);
        String pad3 = INDENT.repeat(depth + 2);
        for (SegmentDef segment : result.segments().values()) {
            sb.append(pad).append("<segmentType name=\"").append(segment.code()).append("\">");
            if (!segment.name().isBlank()) {
                sb.append(" <!-- ").append(comment(segment.name())).append(" -->");
            }
            sb.append('\n');
            sb.append(pad2).append("<sequence>\n");
            for (SegmentElementRef ref : segment.elements()) {
                String tag = ref.composite() ? "composite" : "element";
                sb.append(pad3)
                  .append('<').append(tag)
                  .append(" type=\"").append(ref.refId())
                  .append("\" minOccurs=\"").append(ref.required() ? 1 : 0)
                  .append("\"/>")
                  .append("<!-- ").append(segment.code())
                  .append(String.format("%02d", ref.position()))
                  .append(": ").append(comment(ref.name())).append(" -->\n");
            }
            sb.append(pad2).append("</sequence>\n");
            sb.append(pad).append("</segmentType>\n\n");
        }
    }

    // ------------------------------------------------------------------
    // transaction structure
    // ------------------------------------------------------------------
    private void appendTransaction(StringBuilder sb, ScrapeResult result, int depth) {
        String pad = INDENT.repeat(depth);
        sb.append(pad).append("<transaction>");
        sb.append(" <!-- ").append(result.transaction().id()).append(' ')
          .append(comment(result.transaction().name()))
          .append(" | X12 ").append(result.transaction().releaseCode()).append(" -->\n");
        sb.append(INDENT.repeat(depth + 1)).append("<sequence>\n\n");

        appendArea(sb, "Heading", result.transaction().heading(), depth + 2);
        appendArea(sb, "Detail", result.transaction().detail(), depth + 2);
        appendArea(sb, "Summary", result.transaction().summary(), depth + 2);

        sb.append(INDENT.repeat(depth + 1)).append("</sequence>\n");
        sb.append(pad).append("</transaction>\n");
    }

    private void appendArea(StringBuilder sb, String areaName,
                            List<StructureNode> nodes, int depth) {
        if (nodes.isEmpty()) {
            return;
        }
        sb.append(INDENT.repeat(depth)).append("<!-- ").append(areaName).append(" -->\n");
        appendNodes(sb, nodes, depth);
        sb.append('\n');
    }

    private void appendNodes(StringBuilder sb, List<StructureNode> nodes, int depth) {
        String pad = INDENT.repeat(depth);
        for (StructureNode node : nodes) {
            if (node instanceof StructureNode.SegmentUse segment) {
                sb.append(pad)
                  .append("<segment type=\"").append(segment.code())
                  .append("\" minOccurs=\"").append(segment.mandatory() ? 1 : 0)
                  .append("\" maxOccurs=\"").append(maxOf(segment.maxUse()))
                  .append("\"/>");
                if (!segment.name().isBlank()) {
                    sb.append("<!-- ").append(comment(segment.name())).append(" -->");
                }
                sb.append('\n');
            } else if (node instanceof StructureNode.LoopUse loop) {
                sb.append('\n').append(pad)
                  .append("<!-- ").append(comment(loop.name())).append(" -->\n");
                sb.append(pad)
                  .append("<loop code=\"").append(loop.code())
                  .append("\" minOccurs=\"").append(loop.mandatory() ? 1 : 0)
                  .append("\" maxOccurs=\"").append(maxOf(loop.repeat()))
                  .append("\">\n");
                sb.append(INDENT.repeat(depth + 1)).append("<sequence>\n");
                appendNodes(sb, loop.children(), depth + 2);
                sb.append(INDENT.repeat(depth + 1)).append("</sequence>\n");
                sb.append(pad).append("</loop>\n\n");
            }
        }
    }

    /** -1 means "&gt;1" on the website -> configured unbounded substitute. */
    private int maxOf(int rawCount) {
        return rawCount < 0 ? props.defaultUnboundedMax() : rawCount;
    }

    /** Keeps XML comments well-formed. */
    private static String comment(String text) {
        return text == null ? "" : text.replace("--", "-").trim();
    }
}
