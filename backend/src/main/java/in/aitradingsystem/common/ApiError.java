package in.aitradingsystem.common;

import java.time.Instant;
import java.util.List;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String title,
        String detail,
        String path,
        String correlationId,
        List<String> errors) {
}
