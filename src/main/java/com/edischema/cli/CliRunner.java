package com.edischema.cli;

import com.edischema.service.GenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Command-line mode. When --url or --from-excel is passed the application
 * runs the pipeline and exits instead of staying up as a web service.
 *
 * <pre>
 *   java -jar edi-schema-generator.jar --url=https://www.stedi.com/edi/x12-004010/850
 *   java -jar edi-schema-generator.jar \
 *        --url=https://www.stedi.com/edi/x12-004010/850,https://www.stedi.com/edi/x12-004010/856,https://www.stedi.com/edi/x12-005010/810
 *   java -jar edi-schema-generator.jar --url=... --combined
 *   java -jar edi-schema-generator.jar --from-excel=output/x12-004010/850/850-schema.xlsx
 * </pre>
 */
@Component
public class CliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);

    private final GenerationService generationService;
    private final ConfigurableApplicationContext context;

    public CliRunner(GenerationService generationService,
                     ConfigurableApplicationContext context) {
        this.generationService = generationService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean cliMode = args.containsOption("url") || args.containsOption("from-excel");
        if (!cliMode) {
            log.info("No --url / --from-excel argument given; running as web service. "
                    + "POST /api/generate to trigger a run.");
            return;
        }

        boolean combined = args.containsOption("combined");
        int exitCode = 0;
        try {
            if (args.containsOption("url")) {
                List<String> values = args.getOptionValues("url");
                for (String value : values) {
                    for (String url : value.split(",")) {
                        if (!url.isBlank()) {
                            var summary = generationService
                                    .generateFromUrl(url.trim(), combined);
                            log.info("Generated {} {} ({}): excel={} xml={}",
                                    summary.transactionId(), summary.transactionName(),
                                    summary.releaseCode(), summary.excelFile(),
                                    summary.xmlFiles());
                        }
                    }
                }
            }
            if (args.containsOption("from-excel")) {
                for (String workbook : args.getOptionValues("from-excel")) {
                    var summary = generationService
                            .generateFromExcel(Path.of(workbook.trim()), combined);
                    log.info("Regenerated {} from workbook: xml={}",
                            summary.transactionId(), summary.xmlFiles());
                }
            }
        } catch (Exception e) {
            log.error("Generation failed: {}", e.getMessage(), e);
            exitCode = 1;
        }
        final int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
