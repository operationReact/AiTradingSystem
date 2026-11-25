package in.aitradingsystem.api;

import in.aitradingsystem.notification.*;
import in.aitradingsystem.operations.OperationalHealthService;
import in.aitradingsystem.operations.OperationalIncidentRefreshService;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notifications;
    private final OperationalHealthService health;
    private final OperationalIncidentRefreshService incidents;
    public NotificationController(NotificationService notifications,OperationalHealthService health,OperationalIncidentRefreshService incidents) {
        this.notifications=notifications; this.health=health; this.incidents=incidents;
    }

    @GetMapping
    public NotificationPage list(@RequestParam(defaultValue="true") boolean activeOnly,
        @RequestParam(required=false) NotificationSeverity severity,@RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="20") int size) {
        // Validate bounds before doing projection work for an invalid request.
        if (page<0 || page>1_000_000 || size<1 || size>100) throw new IllegalArgumentException("Invalid notification page/size");
        var projection=incidents.refresh(health.snapshot());
        var result=notifications.list(activeOnly,severity,page,size);
        return new NotificationPage(result.items(),result.page(),result.size(),result.totalItems(),result.totalPages(),projection);
    }
    @GetMapping("/{id}")
    public SystemNotification detail(@PathVariable UUID id) { return notifications.require(id); }

    @PostMapping("/{id}/acknowledge")
    public SystemNotification acknowledge(@PathVariable UUID id,Principal principal) {
        if (principal==null) throw new AccessDeniedException("Authenticated operator required");
        return notifications.acknowledge(id,principal.getName());
    }

    public record NotificationPage(List<SystemNotification> items,int page,int size,long totalItems,int totalPages,
                                    OperationalIncidentRefreshService.ProjectionResult projection) {}
}
