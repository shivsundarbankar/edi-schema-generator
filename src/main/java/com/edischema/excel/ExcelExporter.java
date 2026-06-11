package com.edischema.excel;

import com.edischema.model.CompositeComponent;
import com.edischema.model.CompositeDef;
import com.edischema.model.ElementDef;
import com.edischema.model.ScrapeResult;
import com.edischema.model.SegmentDef;
import com.edischema.model.SegmentElementRef;
import com.edischema.model.StructureNode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Exports the scraped model into an auditable Excel workbook. The workbook is
 * also a valid round-trip source: {@link ExcelImporter} can rebuild the model
 * from it, so the workbook can be reviewed/edited and then converted to XML.
 *
 * Sheets:
 * <ul>
 *   <li><b>Meta</b>       - transaction id, name, release</li>
 *   <li><b>Structure</b>  - flattened structure tree (Level column preserves nesting)</li>
 *   <li><b>Segments</b>   - element/composite references per segment, in sequence</li>
 *   <li><b>Composites</b> - composite component sequences</li>
 *   <li><b>Elements</b>   - deduplicated element dictionary</li>
 * </ul>
 */
@Component
public class ExcelExporter {

    private static final Logger log = LoggerFactory.getLogger(ExcelExporter.class);

    public Path export(ScrapeResult result, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(result.transaction().id() + "-schema.xlsx");

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = headerStyle(workbook);

            writeMeta(workbook, headerStyle, result);
            writeStructure(workbook, headerStyle, result);
            writeSegments(workbook, headerStyle, result);
            writeComposites(workbook, headerStyle, result);
            writeElements(workbook, headerStyle, result);

            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }
        log.info("Wrote {}", file);
        return file;
    }

    private void writeMeta(XSSFWorkbook wb, CellStyle headerStyle, ScrapeResult result) {
        Sheet sheet = wb.createSheet("Meta");
        header(sheet, headerStyle, "Key", "Value");
        row(sheet, 1, "TransactionId", result.transaction().id());
        row(sheet, 2, "TransactionName", result.transaction().name());
        row(sheet, 3, "ReleaseCode", result.transaction().releaseCode());
        autosize(sheet, 2);
    }

    private void writeStructure(XSSFWorkbook wb, CellStyle headerStyle, ScrapeResult result) {
        Sheet sheet = wb.createSheet("Structure");
        header(sheet, headerStyle,
                "Area", "Level", "Kind", "Position", "Code", "Name", "Mandatory", "MaxUse");
        int[] rowIndex = {1};
        writeStructureNodes(sheet, "HEADING", result.transaction().heading(), 0, rowIndex);
        writeStructureNodes(sheet, "DETAIL", result.transaction().detail(), 0, rowIndex);
        writeStructureNodes(sheet, "SUMMARY", result.transaction().summary(), 0, rowIndex);
        autosize(sheet, 8);
    }

    private void writeStructureNodes(Sheet sheet, String area, List<StructureNode> nodes,
                                     int level, int[] rowIndex) {
        for (StructureNode node : nodes) {
            if (node instanceof StructureNode.SegmentUse s) {
                row(sheet, rowIndex[0]++, area, String.valueOf(level), "SEGMENT",
                        s.position(), s.code(), s.name(),
                        s.mandatory() ? "Y" : "N", maxText(s.maxUse()));
            } else if (node instanceof StructureNode.LoopUse loop) {
                row(sheet, rowIndex[0]++, area, String.valueOf(level), "LOOP",
                        "", loop.code(), loop.name(),
                        loop.mandatory() ? "Y" : "N", maxText(loop.repeat()));
                writeStructureNodes(sheet, area, loop.children(), level + 1, rowIndex);
            }
        }
    }

    private void writeSegments(XSSFWorkbook wb, CellStyle headerStyle, ScrapeResult result) {
        Sheet sheet = wb.createSheet("Segments");
        header(sheet, headerStyle,
                "Segment", "SegmentName", "Purpose", "Pos", "RefKind", "Ref", "Name", "Mandatory");
        int rowIndex = 1;
        for (SegmentDef segment : result.segments().values()) {
            for (SegmentElementRef ref : segment.elements()) {
                row(sheet, rowIndex++,
                        segment.code(), segment.name(), segment.purpose(),
                        String.valueOf(ref.position()),
                        ref.composite() ? "COMPOSITE" : "ELEMENT",
                        ref.refId(), ref.name(), ref.required() ? "Y" : "N");
            }
        }
        autosize(sheet, 8);
    }

    private void writeComposites(XSSFWorkbook wb, CellStyle headerStyle, ScrapeResult result) {
        Sheet sheet = wb.createSheet("Composites");
        header(sheet, headerStyle,
                "Composite", "CompositeName", "Pos", "ElementRef", "Mandatory", "ElementName");
        int rowIndex = 1;
        for (CompositeDef composite : result.composites().values()) {
            for (CompositeComponent component : composite.components()) {
                row(sheet, rowIndex++,
                        composite.ref(), composite.name(),
                        String.valueOf(component.position()),
                        component.elementRef(),
                        component.required() ? "Y" : "N",
                        component.name());
            }
        }
        autosize(sheet, 6);
    }

    private void writeElements(XSSFWorkbook wb, CellStyle headerStyle, ScrapeResult result) {
        Sheet sheet = wb.createSheet("Elements");
        header(sheet, headerStyle,
                "Ref", "Number", "Name", "BaseType", "MinLength", "MaxLength");
        int rowIndex = 1;
        for (ElementDef element : result.elements().values()) {
            row(sheet, rowIndex++,
                    element.ref(), element.number(), element.name(), element.baseType(),
                    String.valueOf(element.minLength()), String.valueOf(element.maxLength()));
        }
        autosize(sheet, 6);
    }

    // ------------------------------------------------------------------

    static String maxText(int rawCount) {
        return rawCount < 0 ? ">1" : String.valueOf(rawCount);
    }

    private static void header(Sheet sheet, CellStyle style, String... titles) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < titles.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(titles[i]);
            cell.setCellStyle(style);
        }
        sheet.createFreezePane(0, 1);
    }

    private static void row(Sheet sheet, int index, String... values) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i] == null ? "" : values[i]);
        }
    }

    private static void autosize(Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private static CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}
