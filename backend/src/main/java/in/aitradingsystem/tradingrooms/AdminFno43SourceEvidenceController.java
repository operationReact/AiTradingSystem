package in.aitradingsystem.tradingrooms;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin/operator-only observability; no writes, orders or credentials. */
@RestController
@RequestMapping("/api/admin/research/fno-43")
public class AdminFno43SourceEvidenceController {
    private final Fno43RuntimeSourceAuditService audit;
    private final Clock clock;

    @Autowired
    public AdminFno43SourceEvidenceController(Fno43RuntimeSourceAuditService audit){
        this(audit,Clock.systemUTC());
    }
    AdminFno43SourceEvidenceController(Fno43RuntimeSourceAuditService audit,Clock clock){
        this.audit=java.util.Objects.requireNonNull(audit);
        this.clock=java.util.Objects.requireNonNull(clock);
    }

    @GetMapping("/source-evidence")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<Fno43RuntimeSourceAuditService.AuditReport> evidence(){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(audit.inspect(clock.instant()));
    }
}
