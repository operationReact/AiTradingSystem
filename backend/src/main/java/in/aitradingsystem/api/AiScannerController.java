package in.aitradingsystem.api;

import in.aitradingsystem.scanner.AiScannerService;
import in.aitradingsystem.scanner.AiScannerService.PresentationState;
import in.aitradingsystem.scanner.AiScannerService.ScannerQuery;
import in.aitradingsystem.scanner.AiScannerService.ScannerSnapshot;
import in.aitradingsystem.scanner.AiScannerService.ScannerSort;
import in.aitradingsystem.scanner.AiScannerService.UncertaintyLevel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/scanner")
public class AiScannerController {
    private final AiScannerService scanner;

    public AiScannerController(AiScannerService scanner) {
        this.scanner = scanner;
    }

    @GetMapping
    public ScannerSnapshot scan(
            @RequestParam(required = false) PresentationState state,
            @RequestParam(required = false) String sector,
            @RequestParam(name = "uncertainty", required = false) UncertaintyLevel uncertaintyLevel,
            @RequestParam(required = false) Double minRegimeFit,
            @RequestParam(required = false) Double minLiquidityPercentile,
            @RequestParam(defaultValue = "EXPECTED_NET_EDGE") ScannerSort sort,
            @RequestParam(defaultValue = "50") int limit) {
        return scanner.scan(new ScannerQuery(
                state,
                sector,
                uncertaintyLevel,
                minRegimeFit,
                minLiquidityPercentile,
                sort,
                limit));
    }
}
