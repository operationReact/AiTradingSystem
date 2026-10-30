package in.aitradingsystem.api;

import in.aitradingsystem.audit.AuditQueryService;
import in.aitradingsystem.common.PageResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditQueryService audit;
    public AuditController(AuditQueryService audit) { this.audit=audit; }

    @GetMapping
    public PageResponse<AuditQueryService.AuditEvent> search(@RequestParam(required=false) String eventType,
        @RequestParam(required=false) String entityType,@RequestParam(required=false) String entityId,
        @RequestParam(required=false) String correlationId,@RequestParam(required=false) String actor,
        @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return audit.search(new AuditQueryService.Filter(eventType,entityType,entityId,correlationId,actor,from,to),page,size);
    }
    @GetMapping("/export")
    public ResponseEntity<String> export(@RequestParam(required=false) String eventType,
        @RequestParam(required=false) String entityType,@RequestParam(required=false) String entityId,
        @RequestParam(required=false) String correlationId,@RequestParam(required=false) String actor,
        @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to) {
        String csv=audit.exportCsv(new AuditQueryService.Filter(eventType,entityType,entityId,correlationId,actor,from,to));
        return ResponseEntity.ok().contentType(new MediaType("text","csv",StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"audit.csv\"").body(csv);
    }
}
