package in.aitradingsystem.marketdata;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/market-data/calendar")
public class MarketCalendarController {
    private final MarketSessionCalendar calendar;

    public MarketCalendarController(MarketSessionCalendar calendar) {
        this.calendar = calendar;
    }

    public record CalendarResponse(LocalDate from, LocalDate to, List<MarketSession> sessions) {}

    @GetMapping
    public CalendarResponse calendar(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= 370) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Calendar range must contain 1 to 370 days");
        }
        List<MarketSession> sessions = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            sessions.add(calendar.session(date));
        }
        return new CalendarResponse(from, to, List.copyOf(sessions));
    }
}
