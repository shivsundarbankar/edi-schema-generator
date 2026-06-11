package com.edischema.service;

import com.edischema.config.AppProperties;
import com.edischema.excel.ExcelExporter;
import com.edischema.excel.ExcelImporter;
import com.edischema.model.ReleaseUrl;
import com.edischema.model.ScrapeResult;
import com.edischema.scraper.StediScraper;
import com.edischema.xml.EdiSchemaXmlWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the full pipeline:
 * scrape (any release, URL-driven) -> Excel workbook -> EDISchema v4 XML.
 * Also supports regenerating XML from a (possibly hand-edited) workbook.
 */
@Service
public class GenerationService {

    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);

    private final StediScraper scraper;
    private final ExcelExporter excelExporter;
    private final ExcelImporter excelImporter;
    private final EdiSchemaXmlWriter xmlWriter;
    private final AppProperties props;

    public GenerationService(StediScraper scraper,
                             ExcelExporter excelExporter,
                             ExcelImporter excelImporter,
                             EdiSchemaXmlWriter xmlWriter,
                             AppProperties props) {
        this.scraper = scraper;
        this.excelExporter = excelExporter;
        this.excelImporter = excelImporter;
        this.xmlWriter = xmlWriter;
        this.props = props;
    }

    /** Summary of one generation run. */
    public record GenerationSummary(String transactionId, String transactionName,
                                    String releaseCode, int segmentCount,
                                    int compositeCount, int elementCount,
                                    String excelFile, List<String> xmlFiles) {
    }

    /**
     * Runs the full pipeline for one Stedi URL, e.g.
     * https://www.stedi.com/edi/x12-004010/850 or .../x12-005010/810.
     */
    public GenerationSummary generateFromUrl(String url, boolean combined) {
        ReleaseUrl release = ReleaseUrl.parse(url);
        ScrapeResult result = scraper.scrape(release);
        return writeOutputs(result, combined);
    }

    /** Regenerates XML from a previously exported (and possibly edited) workbook. */
    public GenerationSummary generateFromExcel(Path workbook, boolean combined) {
        try {
            ScrapeResult result = excelImporter.importWorkbook(workbook);
            return writeOutputs(result, combined);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read workbook " + workbook, e);
        }
    }

    private GenerationSummary writeOutputs(ScrapeResult result, boolean combined) {
        try {
            Path outputDir = Path.of(props.outputDir(),
                    "x12-" + result.transaction().releaseCode(),
                    result.transaction().id());

            Path excelFile = excelExporter.export(result, outputDir);
            List<Path> xmlFiles = xmlWriter.write(result, outputDir, combined);

            List<String> xmlPaths = new ArrayList<>();
            xmlFiles.forEach(p -> xmlPaths.add(p.toAbsolutePath().toString()));

            GenerationSummary summary = new GenerationSummary(
                    result.transaction().id(),
                    result.transaction().name(),
                    result.transaction().releaseCode(),
                    result.segments().size(),
                    result.composites().size(),
                    result.elements().size(),
                    excelFile.toAbsolutePath().toString(),
                    xmlPaths);
            log.info("Generation complete: {}", summary);
            return summary;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write output files", e);
        }
    }
}
