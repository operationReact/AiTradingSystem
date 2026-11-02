package in.aitradingsystem.news;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/news/research")
@Validated
public class NewsResearchController {
    private final NewsResearchExportService exports;

    public NewsResearchController(NewsResearchExportService exports) {
        this.exports = exports;
    }

    @GetMapping("/events")
    public NewsResearchExport events(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fromInclusive,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant toExclusive,
        @RequestParam(defaultValue = "5000") @Min(1) @Max(20_000) int limit) {
        return exports.export(fromInclusive, toExclusive, limit);
    }
}
