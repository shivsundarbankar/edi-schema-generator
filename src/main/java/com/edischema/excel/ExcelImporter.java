package com.edischema.excel;

import com.edischema.model.CompositeComponent;
import com.edischema.model.CompositeDef;
import com.edischema.model.ElementDef;
import com.edischema.model.ScrapeResult;
import com.edischema.model.SegmentDef;
import com.edischema.model.SegmentElementRef;
import com.edischema.model.StructureNode;
import com.edischema.model.TransactionSetDoc;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds a {@link ScrapeResult} from a workbook produced by
 * {@link ExcelExporter}. This enables the requested two-step workflow:
 * scrape -&gt; Excel (review / hand-edit) -&gt; XML.
 */
@Component
public class ExcelImporter {

    private static final Logger log = LoggerFactory.getLogger(ExcelImporter.class);
    private final DataFormatter formatter = new DataFormatter();

    public ScrapeResult importWorkbook(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file);
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {

            Map<String, String> meta = readMeta(workbook.getSheet("Meta"));
            String txnId = meta.getOrDefault("TransactionId", "000");
            String txnName = meta.getOrDefault("TransactionName", "");
            String release = meta.getOrDefault("ReleaseCode", "");

            AreaStructures areas = readStructure(workbook.getSheet("Structure"));
            Map<String, SegmentDef> segments = readSegments(workbook.getSheet("Segments"));
            Map<String, CompositeDef> composites = readComposites(workbook.getSheet("Composites"));
            Map<String, ElementDef> elements = readElements(workbook.getSheet("Elements"));

            TransactionSetDoc doc = new TransactionSetDoc(txnId, txnName, release,
                    areas.heading, areas.detail, areas.summary);
            log.info("Imported workbook {}: txn {}, {} segments, {} composites, {} elements",
                    file, txnId, segments.size(), composites.size(), elements.size());
            return new ScrapeResult(doc, segments, composites, elements);
        }
    }

    private Map<String, String> readMeta(Sheet sheet) {
        Map<String, String> meta = new LinkedHashMap<>();
        if (sheet == null) {
            return meta;
        }
        for (Row row : sheet) {
            if (row.getRowNum() == 0) {
                continue;
            }
            String key = text(row, 0);
            if (!key.isEmpty()) {
                meta.put(key, text(row, 1));
            }
        }
        return meta;
    }

    private record AreaStructures(List<StructureNode> heading,
                                  List<StructureNode> detail,
                                  List<StructureNode> summary) {
    }

    /**
     * Rebuilds the nested structure tree from the flattened sheet using the
     * Level column: a row at level N is a child of the most recent LOOP row
     * at level N-1.
     */
    private AreaStructures readStructure(Sheet sheet) {
        List<StructureNode> heading = new ArrayList<>();
        List<StructureNode> detail = new ArrayList<>();
        List<StructureNode> summary = new ArrayList<>();
        if (sheet == null) {
            return new AreaStructures(heading, detail, summary);
        }

        String currentArea = "";
        List<StructureNode> currentRoot = heading;
        Deque<LoopBuilder> stack = new ArrayDeque<>();

        for (Row row : sheet) {
            if (row.getRowNum() == 0) {
                continue;
            }
            String area = text(row, 0);
            if (area.isEmpty()) {
                continue;
            }
            int level = parseInt(text(row, 1), 0);
            String kind = text(row, 2);
            String position = text(row, 3);
            String code = text(row, 4);
            String name = text(row, 5);
            boolean mandatory = text(row, 6).equalsIgnoreCase("Y");
            int max = parseMax(text(row, 7));

            if (!area.equals(currentArea)) {
                closeLoops(stack, 0, currentRoot);
                currentArea = area;
                currentRoot = switch (area.toUpperCase()) {
                    case "DETAIL" -> detail;
                    case "SUMMARY" -> summary;
                    default -> heading;
                };
            }

            // close loops that are deeper than (or as deep as) this row's level
            closeLoops(stack, level, currentRoot);

            if (kind.equalsIgnoreCase("LOOP")) {
                stack.push(new LoopBuilder(code, name, mandatory, max));
            } else {
                StructureNode segment =
                        new StructureNode.SegmentUse(position, code, name, mandatory, max);
                if (stack.isEmpty()) {
                    currentRoot.add(segment);
                } else {
                    stack.peek().children.add(segment);
                }
            }
        }
        closeLoops(stack, 0, currentRoot);
        return new AreaStructures(heading, detail, summary);
    }

    /** Pops and finalizes open loops until the stack depth equals {@code level}. */
    private static void closeLoops(Deque<LoopBuilder> stack, int level,
                                   List<StructureNode> root) {
        while (stack.size() > level) {
            LoopBuilder done = stack.pop();
            StructureNode.LoopUse loop = done.build();
            if (stack.isEmpty()) {
                root.add(loop);
            } else {
                stack.peek().children.add(loop);
            }
        }
    }

    /** Mutable builder for a loop while its children are being collected. */
    private static final class LoopBuilder {
        final String code;
        final String name;
        final boolean mandatory;
        final int repeat;
        final List<StructureNode> children = new ArrayList<>();

        LoopBuilder(String code, String name, boolean mandatory, int repeat) {
            this.code = code;
            this.name = name;
            this.mandatory = mandatory;
            this.repeat = repeat;
        }

        StructureNode.LoopUse build() {
            return new StructureNode.LoopUse(code, name, mandatory, repeat,
                    List.copyOf(children));
        }
    }

    private Map<String, SegmentDef> readSegments(Sheet sheet) {
        Map<String, List<SegmentElementRef>> refsBySegment = new LinkedHashMap<>();
        Map<String, String[]> metaBySegment = new LinkedHashMap<>();
        if (sheet != null) {
            for (Row row : sheet) {
                if (row.getRowNum() == 0) {
                    continue;
                }
                String code = text(row, 0);
                if (code.isEmpty()) {
                    continue;
                }
                metaBySegment.putIfAbsent(code, new String[]{text(row, 1), text(row, 2)});
                refsBySegment.computeIfAbsent(code, k -> new ArrayList<>())
                        .add(new SegmentElementRef(
                                parseInt(text(row, 3), 1),
                                text(row, 5),
                                text(row, 4).equalsIgnoreCase("COMPOSITE"),
                                text(row, 7).equalsIgnoreCase("Y"),
                                text(row, 6)));
            }
        }
        Map<String, SegmentDef> segments = new LinkedHashMap<>();
        refsBySegment.forEach((code, refs) -> {
            String[] meta = metaBySegment.get(code);
            segments.put(code, new SegmentDef(code, meta[0], meta[1], List.copyOf(refs)));
        });
        return segments;
    }

    private Map<String, CompositeDef> readComposites(Sheet sheet) {
        Map<String, List<CompositeComponent>> componentsByComposite = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        if (sheet != null) {
            for (Row row : sheet) {
                if (row.getRowNum() == 0) {
                    continue;
                }
                String ref = text(row, 0);
                if (ref.isEmpty()) {
                    continue;
                }
                names.putIfAbsent(ref, text(row, 1));
                componentsByComposite.computeIfAbsent(ref, k -> new ArrayList<>())
                        .add(new CompositeComponent(
                                parseInt(text(row, 2), 1),
                                text(row, 3),
                                text(row, 4).equalsIgnoreCase("Y"),
                                text(row, 5)));
            }
        }
        Map<String, CompositeDef> composites = new LinkedHashMap<>();
        componentsByComposite.forEach((ref, components) ->
                composites.put(ref, new CompositeDef(ref, names.get(ref),
                        List.copyOf(components))));
        return composites;
    }

    private Map<String, ElementDef> readElements(Sheet sheet) {
        Map<String, ElementDef> elements = new LinkedHashMap<>();
        if (sheet != null) {
            for (Row row : sheet) {
                if (row.getRowNum() == 0) {
                    continue;
                }
                String ref = text(row, 0);
                if (ref.isEmpty()) {
                    continue;
                }
                elements.put(ref, new ElementDef(
                        ref,
                        text(row, 1),
                        text(row, 2),
                        text(row, 3),
                        parseInt(text(row, 4), 1),
                        parseInt(text(row, 5), 1)));
            }
        }
        return elements;
    }

    // ------------------------------------------------------------------

    private String text(Row row, int column) {
        Cell cell = row.getCell(column);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** ">1" in the workbook maps back to the internal unbounded marker -1. */
    private static int parseMax(String value) {
        String v = value.trim();
        if (v.startsWith(">")) {
            return -1;
        }
        return parseInt(v, 1);
    }
}
