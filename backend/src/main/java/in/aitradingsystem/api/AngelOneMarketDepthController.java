package in.aitradingsystem.api;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterException;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.AngelOneException;
import in.aitradingsystem.provider.angelone.AngelOneMarketDepthService;
import in.aitradingsystem.provider.angelone.AngelOneMarketDepthService.MarketDepthResearchExport;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated read-only research surface for exact active-catalogue market-depth evidence. */
@RestController
@RequestMapping("/api/providers/angel-one/research")
public class AngelOneMarketDepthController {
    public record MarketDepthRequest(List<Long> instrumentIds) {}

    private final InstrumentMasterService instruments;
    private final AngelOneMarketDepthService depth;

    public AngelOneMarketDepthController(InstrumentMasterService instruments, AngelOneMarketDepthService depth) {
        this.instruments = instruments;
        this.depth = depth;
    }

    @PostMapping("/market-depth")
    public MarketDepthResearchExport marketDepth(@RequestBody(required = false) MarketDepthRequest request) {
        if (request == null || request.instrumentIds() == null
                || request.instrumentIds().isEmpty() || request.instrumentIds().size() > 50) {
            throw new IllegalArgumentException("instrumentIds must contain between 1 and 50 active catalogue IDs");
        }
        Set<Long> unique = new HashSet<>();
        List<Instrument> resolved = new ArrayList<>(request.instrumentIds().size());
        for (Long id : request.instrumentIds()) {
            if (id == null || id < 1 || !unique.add(id)) {
                throw new IllegalArgumentException("instrumentIds must be positive and unique");
            }
            Instrument instrument = instruments.requireActiveInstrument(id);
            if (!"NSE".equals(instrument.exchange()) || !"EQ".equals(instrument.instrumentType())) {
                throw new IllegalArgumentException("market-depth research currently accepts NSE cash equities only");
            }
            resolved.add(instrument);
        }
        return depth.fetch(resolved);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(InstrumentMasterException.class)
    ProblemDetail instrumentFailure(InstrumentMasterException ex) {
        HttpStatus status = "INSTRUMENT_NOT_FOUND".equals(ex.code())
                ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setProperty("code", ex.code());
        return problem;
    }

    @ExceptionHandler(AngelOneException.class)
    ProblemDetail providerFailure(AngelOneException ex) {
        HttpStatus status = switch (ex.kind()) {
            case NOT_CONFIGURED, UNAVAILABLE, SESSION_EXPIRED -> HttpStatus.SERVICE_UNAVAILABLE;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case REJECTED, INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setProperty("providerCode", ex.code());
        return problem;
    }
}
