package in.aitradingsystem.instrument;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/instruments")
public class InstrumentMasterController {
    private final InstrumentMasterService service;

    public InstrumentMasterController(InstrumentMasterService service) {
        this.service = service;
    }

    @GetMapping("/search")
    public InstrumentSearchResponse search(
            @RequestParam(required = false) @Size(max = 100)
            @Pattern(regexp = "[A-Za-z0-9 .&/_-]*") String q,
            @RequestParam(required = false) @Pattern(regexp = "NSE|BSE|NFO|BFO|MCX|CDS|BCD") String exchange,
            @RequestParam(required = false) @Size(max = 30)
            @Pattern(regexp = "[A-Za-z0-9_-]*") String type,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100_000) int size) {
        return service.search(q, exchange, type, page, size);
    }

    @GetMapping("/status")
    public InstrumentMasterRepository.CatalogueSummary status() {
        return service.catalogueSummary();
    }

    /** Explicit operator-triggered import; the source is fetched only by the backend. */
    @PostMapping("/import")
    public InstrumentImportSummary importLatest() {
        return service.importLatest();
    }

    @GetMapping("/imports")
    public List<InstrumentImportSummary> recentImports(
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        return service.recentImports(limit);
    }

    @GetMapping("/imports/{id}")
    public InstrumentImportSummary importById(@PathVariable UUID id) {
        return service.importSummary(id);
    }

    @ExceptionHandler(InstrumentMasterException.class)
    ProblemDetail handleInstrumentError(InstrumentMasterException exception) {
        HttpStatus status = switch (exception.code()) {
            case "IMPORT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "INVALID_PAGE", "INVALID_PAGE_SIZE", "INVALID_LIMIT" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setProperty("code", exception.code());
        return problem;
    }
}
