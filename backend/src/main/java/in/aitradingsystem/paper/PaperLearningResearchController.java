package in.aitradingsystem.paper;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/paper-learning/research")
public class PaperLearningResearchController {
    private final PaperLearningResearchExportService service;

    public PaperLearningResearchController(PaperLearningResearchExportService service) {
        this.service = service;
    }

    @GetMapping("/closed-trades")
    public PaperLearningResearchExport closedTrades(
            @RequestParam(defaultValue = "1000") @Min(1) @Max(5000) int limit) {
        return service.export(limit);
    }
}
