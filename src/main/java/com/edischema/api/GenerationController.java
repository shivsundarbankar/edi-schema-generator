package com.edischema.api;

import com.edischema.service.GenerationService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * REST API for the generator.
 *
 * <pre>
 * POST /api/generate
 *   { "url": "https://www.stedi.com/edi/x12-004010/850", "combined": false }
 *
 * POST /api/generate/batch
 *   { "urls": ["https://www.stedi.com/edi/x12-004010/850",
 *              "https://www.stedi.com/edi/x12-005010/810"], "combined": false }
 *
 * POST /api/generate/from-excel
 *   { "workbookPath": "output/x12-004010/850/850-schema.xlsx", "combined": false }
 * </pre>
 */
@RestController
@RequestMapping("/api")
@Validated
public class GenerationController {

    private final GenerationService generationService;

    public GenerationController(GenerationService generationService) {
        this.generationService = generationService;
    }

    public record GenerateRequest(@NotBlank String url, boolean combined) {
    }

    public record BatchRequest(List<String> urls, boolean combined) {
    }

    public record FromExcelRequest(@NotBlank String workbookPath, boolean combined) {
    }

    @PostMapping("/generate")
    public GenerationService.GenerationSummary generate(@RequestBody GenerateRequest request) {
        return generationService.generateFromUrl(request.url(), request.combined());
    }

    @PostMapping("/generate/batch")
    public List<GenerationService.GenerationSummary> generateBatch(
            @RequestBody BatchRequest request) {
        return request.urls().stream()
                .map(url -> generationService.generateFromUrl(url, request.combined()))
                .toList();
    }

    @PostMapping("/generate/from-excel")
    public GenerationService.GenerationSummary generateFromExcel(
            @RequestBody FromExcelRequest request) {
        return generationService.generateFromExcel(
                Path.of(request.workbookPath()), request.combined());
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @ExceptionHandler({IllegalArgumentException.class, RuntimeException.class})
    public ResponseEntity<Map<String, String>> onError(RuntimeException e) {
        HttpStatus status = e instanceof IllegalArgumentException
                ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status)
                .body(Map.of("error", e.getClass().getSimpleName(),
                        "message", String.valueOf(e.getMessage())));
    }
}
