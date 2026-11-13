package in.aitradingsystem.mind;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Evidence-backed Mintick Mind completion tracker (issue #998).
 *
 * <p>The audited inventory is a versioned classpath manifest whose declared statuses never exceed
 * {@code TESTED}. Only server-side runtime evidence recorded by engine code on a known backend
 * revision can produce {@code RUNTIME_VERIFIED} (the only "DONE" state). Customers can attach notes
 * or recheck requests but have no path that changes a status.
 */
@Service
public class MindCapabilityProgressService {
    public static final String CONTRACT_VERSION = "mind.progress.v1";
    static final String MANIFEST_RESOURCE = "/mind/capability-manifest.v1.json";
    static final Duration VERIFICATION_TTL = Duration.ofDays(7);
    private static final Pattern REVISION = Pattern.compile("[0-9a-f]{7,40}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_:.-]{8,128}");
    private static final Set<Status> DECLARABLE = Set.of(Status.NOT_STARTED, Status.MOCK,
        Status.IMPLEMENTING, Status.CODE_COMPLETE, Status.TESTED, Status.DATA_BLOCKED,
        Status.RIGHTS_BLOCKED);
    private static final Set<Status> VERIFIABLE = Set.of(Status.CODE_COMPLETE, Status.TESTED);
    /** Ordered PAPER chain; the current stage is the first link that is not runtime verified. */
    static final List<String> PAPER_CHAIN = List.of("engine.data-gate", "engine.strategy-eval",
        "engine.risk-gates", "engine.strategy-to-paper", "engine.paper-submit",
        "engine.accounting", "paper.vertical-slice");

    public enum Status { NOT_STARTED, MOCK, IMPLEMENTING, CODE_COMPLETE, TESTED, DEPLOYED,
        RUNTIME_VERIFIED, DATA_BLOCKED, RIGHTS_BLOCKED, FAILED }

    public enum EventType { RUNTIME_VERIFIED, RUNTIME_FAILED, DATA_BLOCKED, NOTE, RECHECK_REQUESTED }

    public record EvidenceLink(String kind, String path, String ref) {}
    public record Implementation(String code, String version, String match, String runtime) {}
    public record ManifestItem(String id, String kind, String name, String group, String parentId,
                               String owner, String status, String blockerCode,
                               List<String> requiredInputs, List<String> dependsOn,
                               Implementation implementation, List<EvidenceLink> evidence,
                               List<String> missing, String note) {}
    public record Manifest(String manifestVersion, String auditedAt, List<String> sourceIssues,
                           Map<String, Integer> denominators, List<ManifestItem> items) {}

    public record Event(UUID id, String capabilityId, EventType type, String backendRevision,
                        String datasetVersion, String evidenceRef, String detail, String actor,
                        Instant observedAt, Instant recordedAt) {}

    public record Runtime(Instant lastVerifiedAt, Instant lastFailedAt, Instant lastBlockedAt,
                          String verifiedRevision, String datasetVersion, String evidenceRef,
                          String runtimeNote, Instant recheckRequestedAt, int ownNotes) {}

    public record Item(String id, String kind, String name, String group, String parentId,
                       String owner, Status status, Status declaredStatus, boolean done,
                       String blockerCode, List<String> requiredInputs, List<String> dependsOn,
                       Implementation implementation, List<EvidenceLink> evidence,
                       List<String> missing, String note, Runtime runtime) {}

    public record KindSummary(String kind, int denominator, int done, int tested,
                              int inProgress, int blocked, int mock, int notStarted, int failed,
                              Map<Status, Integer> byStatus) {}

    public record PaperStage(String capabilityId, String name, Status status) {}

    public record Progress(String contractVersion, String manifestVersion, String auditedAt,
                           Instant generatedAt, String backendRevision, boolean revisionKnown,
                           List<String> sourceIssues, PaperStage paperStage,
                           List<KindSummary> summaries, List<Item> items) {}

    public record Detail(String contractVersion, Instant generatedAt, String backendRevision,
                         Item item, List<Event> history) {}

    private final JdbcClient jdbc;
    private final Clock clock;
    private final String backendRevision;
    private final Manifest manifest;
    private final Map<String, ManifestItem> byId;

    @org.springframework.beans.factory.annotation.Autowired
    public MindCapabilityProgressService(JdbcClient jdbc, Clock clock,
            @Value("${app.build.revision:${APP_BUILD_REVISION:unknown}}") String backendRevision) {
        this(jdbc, clock, backendRevision, loadManifest());
    }

    MindCapabilityProgressService(JdbcClient jdbc, Clock clock, String backendRevision,
                                  Manifest manifest) {
        this.jdbc = jdbc;
        this.clock = clock;
        String revision = backendRevision == null ? "unknown" : backendRevision.trim().toLowerCase();
        this.backendRevision = revision.isEmpty() ? "unknown" : revision;
        this.manifest = validate(manifest);
        Map<String, ManifestItem> index = new LinkedHashMap<>();
        for (ManifestItem item : manifest.items()) index.put(item.id(), item);
        this.byId = Map.copyOf(index);
    }

    public String backendRevision() { return backendRevision; }

    public boolean revisionKnown() { return REVISION.matcher(backendRevision).matches(); }

    public boolean knows(String capabilityId) { return byId.containsKey(capabilityId); }

    @Transactional(readOnly = true)
    public Progress inspect(UUID viewer) {
        Instant now = clock.instant();
        Map<String, List<Event>> events = eventsByCapability(null);
        List<Item> items = new ArrayList<>(manifest.items().size());
        for (ManifestItem declared : manifest.items()) {
            items.add(evaluate(declared, events.getOrDefault(declared.id(), List.of()), viewer, now));
        }
        Map<String, Item> evaluated = new LinkedHashMap<>();
        for (Item item : items) evaluated.put(item.id(), item);
        return new Progress(CONTRACT_VERSION, manifest.manifestVersion(), manifest.auditedAt(), now,
            backendRevision, revisionKnown(), manifest.sourceIssues(), paperStage(evaluated),
            summaries(items), List.copyOf(items));
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID viewer, String capabilityId) {
        ManifestItem declared = requireItem(capabilityId);
        Instant now = clock.instant();
        List<Event> events = eventsByCapability(capabilityId).getOrDefault(capabilityId, List.of());
        String viewerActor = viewer == null ? null : customerActor(viewer);
        List<Event> visible = events.stream()
            .filter(e -> isRuntime(e.type()) || e.actor().equals(viewerActor))
            .limit(30).toList();
        return new Detail(CONTRACT_VERSION, now, backendRevision,
            evaluate(declared, events, viewer, now), visible);
    }

    /**
     * Engine-only runtime evidence. Idempotent on {@code idempotencyKey}. Not exposed over HTTP.
     */
    @Transactional
    public Event recordRuntime(String capabilityId, EventType type, String datasetVersion,
                               String evidenceRef, String detail, String idempotencyKey,
                               Instant observedAt) {
        if (type == null || !isRuntime(type)) throw new IllegalArgumentException("runtime event type required");
        requireItem(capabilityId);
        if (observedAt == null || observedAt.isAfter(clock.instant().plusSeconds(5))) {
            throw new IllegalArgumentException("observedAt must not be in the future");
        }
        return insert(capabilityId, type, datasetVersion, evidenceRef, detail, "SYSTEM",
            idempotencyKey, observedAt);
    }

    /** Customer annotation; never changes a status. */
    @Transactional
    public Event addNote(UUID owner, String capabilityId, EventType type, String text,
                         String idempotencyKey) {
        if (owner == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer required");
        if (type != EventType.NOTE && type != EventType.RECHECK_REQUESTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only NOTE or RECHECK_REQUESTED");
        }
        String body = text == null ? "" : text.strip();
        if (body.length() > 500 || (type == EventType.NOTE && body.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Note must be 1-500 characters");
        }
        if (idempotencyKey == null || !KEY.matcher(idempotencyKey).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        }
        requireItem(capabilityId);
        String scopedKey = "note:" + owner + ":" + idempotencyKey;
        return insert(capabilityId, type, null, null, body.isEmpty() ? null : body,
            customerActor(owner), scopedKey, clock.instant());
    }

    // ------------------------------------------------------------------ evaluation

    Item evaluate(ManifestItem declared, List<Event> events, UUID viewer, Instant now) {
        Status declaredStatus = Status.valueOf(declared.status());
        Status status = declaredStatus;
        String blocker = declared.blockerCode();
        Event lastVerified = null, lastFailed = null, lastBlocked = null, lastRuntime = null;
        Instant recheck = null;
        int ownNotes = 0;
        String viewerActor = viewer == null ? null : customerActor(viewer);
        for (Event event : events) { // newest first
            switch (event.type()) {
                case RUNTIME_VERIFIED -> { if (lastVerified == null) lastVerified = event; }
                case RUNTIME_FAILED -> { if (lastFailed == null) lastFailed = event; }
                case DATA_BLOCKED -> { if (lastBlocked == null) lastBlocked = event; }
                case RECHECK_REQUESTED -> {
                    if (recheck == null && event.actor().equals(viewerActor)) recheck = event.recordedAt();
                    if (event.actor().equals(viewerActor)) ownNotes++;
                }
                case NOTE -> { if (event.actor().equals(viewerActor)) ownNotes++; }
            }
            if (lastRuntime == null && isRuntime(event.type())) lastRuntime = event;
        }
        if (VERIFIABLE.contains(declaredStatus) && lastRuntime != null) {
            switch (lastRuntime.type()) {
                case RUNTIME_FAILED -> { status = Status.FAILED; blocker = "RUNTIME_FAILED"; }
                case DATA_BLOCKED -> { status = Status.DATA_BLOCKED; blocker = "RUNTIME_DATA_BLOCKED"; }
                case RUNTIME_VERIFIED -> {
                    if (!revisionKnown()) {
                        blocker = "BACKEND_REVISION_UNKNOWN";
                    } else if (!backendRevision.equals(lastRuntime.backendRevision())) {
                        blocker = "REVERIFY_ON_CURRENT_REVISION";
                    } else if (lastRuntime.observedAt().isBefore(now.minus(VERIFICATION_TTL))) {
                        blocker = "VERIFICATION_EXPIRED";
                    } else {
                        status = Status.RUNTIME_VERIFIED;
                        blocker = null;
                    }
                }
                default -> { }
            }
        }
        Runtime runtime = new Runtime(
            lastVerified == null ? null : lastVerified.observedAt(),
            lastFailed == null ? null : lastFailed.observedAt(),
            lastBlocked == null ? null : lastBlocked.observedAt(),
            lastVerified == null ? null : lastVerified.backendRevision(),
            lastRuntime == null ? null : lastRuntime.datasetVersion(),
            lastRuntime == null ? null : lastRuntime.evidenceRef(),
            lastRuntime == null ? null : lastRuntime.detail(),
            recheck, ownNotes);
        return new Item(declared.id(), declared.kind(), declared.name(), declared.group(),
            declared.parentId(), declared.owner(), status, declaredStatus,
            status == Status.RUNTIME_VERIFIED, blocker, nz(declared.requiredInputs()),
            nz(declared.dependsOn()), declared.implementation(), nz(declared.evidence()),
            nz(declared.missing()), declared.note(), runtime);
    }

    static List<KindSummary> summaries(List<Item> items) {
        Map<String, EnumMap<Status, Integer>> counts = new LinkedHashMap<>();
        for (Item item : items) {
            counts.computeIfAbsent(item.kind(), k -> new EnumMap<>(Status.class))
                .merge(item.status(), 1, Integer::sum);
        }
        List<KindSummary> out = new ArrayList<>();
        counts.forEach((kind, byStatus) -> {
            int total = byStatus.values().stream().mapToInt(Integer::intValue).sum();
            out.add(new KindSummary(kind, total,
                get(byStatus, Status.RUNTIME_VERIFIED),
                get(byStatus, Status.TESTED),
                get(byStatus, Status.IMPLEMENTING) + get(byStatus, Status.CODE_COMPLETE)
                    + get(byStatus, Status.DEPLOYED),
                get(byStatus, Status.DATA_BLOCKED) + get(byStatus, Status.RIGHTS_BLOCKED),
                get(byStatus, Status.MOCK), get(byStatus, Status.NOT_STARTED),
                get(byStatus, Status.FAILED), Map.copyOf(byStatus)));
        });
        return List.copyOf(out);
    }

    static PaperStage paperStage(Map<String, Item> items) {
        for (String id : PAPER_CHAIN) {
            Item item = items.get(id);
            if (item == null) return new PaperStage(id, id, Status.NOT_STARTED);
            if (item.status() != Status.RUNTIME_VERIFIED) {
                return new PaperStage(item.id(), item.name(), item.status());
            }
        }
        Item last = items.get(PAPER_CHAIN.getLast());
        return new PaperStage(last.id(), last.name(), last.status());
    }

    // ------------------------------------------------------------------ persistence

    private Event insert(String capabilityId, EventType type, String datasetVersion,
                         String evidenceRef, String detail, String actor, String key,
                         Instant observedAt) {
        if (key == null || key.length() > 128 || key.isBlank()) {
            throw new IllegalArgumentException("idempotency key required");
        }
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO mind_capability_event
                    (id,capability_id,event_type,backend_revision,dataset_version,
                     evidence_ref,detail,actor,idempotency_key,observed_at)
                VALUES (:id,:capability,:type,:revision,:dataset,:ref,:detail,:actor,:key,:observed)
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
            .param("id", id).param("capability", capabilityId).param("type", type.name())
            .param("revision", backendRevision).param("dataset", truncate(datasetVersion, 128))
            .param("ref", truncate(evidenceRef, 256)).param("detail", truncate(detail, 1000))
            .param("actor", actor).param("key", key)
            .param("observed", java.sql.Timestamp.from(observedAt)).update();
        Event stored = jdbc.sql(SELECT + " WHERE idempotency_key=:key")
            .param("key", key).query((rs, row) -> map(rs)).single();
        if (inserted == 0 && (!stored.capabilityId().equals(capabilityId) || stored.type() != type
                || !stored.actor().equals(actor))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Idempotency key already used for different evidence");
        }
        return stored;
    }

    private static final String SELECT = """
            SELECT id,capability_id,event_type,backend_revision,dataset_version,evidence_ref,
                   detail,actor,observed_at,recorded_at
            FROM mind_capability_event""";

    private Map<String, List<Event>> eventsByCapability(String capabilityId) {
        List<Event> rows = capabilityId == null
            ? jdbc.sql(SELECT + " ORDER BY recorded_at DESC, id DESC LIMIT 20000")
                .query((rs, row) -> map(rs)).list()
            : jdbc.sql(SELECT + " WHERE capability_id=:id ORDER BY recorded_at DESC, id DESC LIMIT 500")
                .param("id", capabilityId).query((rs, row) -> map(rs)).list();
        Map<String, List<Event>> grouped = new LinkedHashMap<>();
        for (Event event : rows) grouped.computeIfAbsent(event.capabilityId(), k -> new ArrayList<>()).add(event);
        return grouped;
    }

    private static Event map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Event(rs.getObject("id", UUID.class), rs.getString("capability_id"),
            EventType.valueOf(rs.getString("event_type")), rs.getString("backend_revision"),
            rs.getString("dataset_version"), rs.getString("evidence_ref"), rs.getString("detail"),
            rs.getString("actor"), rs.getTimestamp("observed_at").toInstant(),
            rs.getTimestamp("recorded_at").toInstant());
    }

    // ------------------------------------------------------------------ manifest

    static Manifest loadManifest() {
        try (InputStream in = MindCapabilityProgressService.class.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (in == null) throw new IllegalStateException("Mind capability manifest missing");
            return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .readValue(in, Manifest.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Mind capability manifest unreadable", ex);
        }
    }

    static Manifest validate(Manifest manifest) {
        if (manifest == null || manifest.items() == null || manifest.items().isEmpty()
                || manifest.manifestVersion() == null || manifest.denominators() == null) {
            throw new IllegalStateException("Mind capability manifest incomplete");
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> ids = new java.util.HashSet<>();
        for (ManifestItem item : manifest.items()) {
            if (item.id() == null || !ids.add(item.id())) {
                throw new IllegalStateException("Duplicate or missing capability id: " + item.id());
            }
            Status status;
            try {
                status = Status.valueOf(item.status());
            } catch (RuntimeException ex) {
                throw new IllegalStateException("Unknown status for " + item.id());
            }
            if (!DECLARABLE.contains(status)) {
                throw new IllegalStateException("Manifest cannot declare runtime status for " + item.id());
            }
            counts.merge(item.kind(), 1, Integer::sum);
        }
        manifest.denominators().forEach((kind, expected) -> {
            if (!expected.equals(counts.get(kind))) {
                throw new IllegalStateException("Denominator mismatch for " + kind);
            }
        });
        return manifest;
    }

    private ManifestItem requireItem(String capabilityId) {
        return Optional.ofNullable(capabilityId).map(byId::get)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown capability"));
    }

    private static boolean isRuntime(EventType type) {
        return type == EventType.RUNTIME_VERIFIED || type == EventType.RUNTIME_FAILED
            || type == EventType.DATA_BLOCKED;
    }

    static String customerActor(UUID owner) { return "CUSTOMER:" + owner; }

    private static int get(Map<Status, Integer> map, Status status) {
        return map.getOrDefault(status, 0);
    }

    private static <T> List<T> nz(List<T> list) { return list == null ? List.of() : List.copyOf(list); }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
