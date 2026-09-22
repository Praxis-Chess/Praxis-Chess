package com.praxis.service.settings;

import com.praxis.config.AppProperties;
import com.praxis.config.PraxisClock;
import com.praxis.domain.AnalysisSettings;
import com.praxis.domain.AnalysisTiming;
import com.praxis.domain.AppSettings;
import com.praxis.dto.SettingsDto.*;
import com.praxis.service.analysis.AnalysisProfile;
import com.praxis.repository.AnalysisSettingsRepository;
import com.praxis.repository.AnalysisTimingRepository;
import com.praxis.repository.AppSettingsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * The Settings page's backend: date ranges, versioned engine settings,
 * coverage and time estimates.
 *
 * The design rule that everything here follows: <b>settings are recorded, not
 * just applied</b>. A changed engine configuration is a new
 * {@link AnalysisSettings} row, and every analysed game points at the row it was
 * analysed under — because depth changes the ruler, and comparisons across
 * rulers have to be visible rather than silent.
 */
@Service
public class SettingsService {

    // ── bounds — validated at the API, never silently clamped ─────────────────
    public static final int SWEEP_MIN = 50, SWEEP_MAX = 1_000;
    public static final int DEPTH_MIN = 12, DEPTH_MAX = 30;
    public static final int LINES_MIN = 1, LINES_MAX = 10;
    public static final int EXPLANATIONS_MAX = 50;
    /** Chess.com launched in 2007; there are no archives before it. */
    public static final LocalDate EARLIEST = LocalDate.of(2007, 1, 1);
    /** PlayImprove polls for 120 × 2.5 s before giving up on a practice report. */
    public static final long PRACTICE_BUDGET_MS = 300_000;

    private final AnalysisSettingsRepository versions;
    private final AppSettingsRepository app;
    private final AnalysisTimingRepository timings;
    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final PraxisClock clock;

    public SettingsService(AnalysisSettingsRepository versions, AppSettingsRepository app,
                           AnalysisTimingRepository timings, JdbcTemplate jdbc,
                           AppProperties props, PraxisClock clock) {
        this.versions = versions;
        this.app = app;
        this.timings = timings;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    // ── active configuration ─────────────────────────────────────────────────

    public record Active(AnalysisSettings settings, AnalysisProfile profile) {
        public Long id() { return settings.getId(); }
    }

    /**
     * The configuration new analysis runs use. Falls back to creating the v0 row
     * from the built-in profile, so analysis never fails for want of a setting
     * even if SettingsBootstrap hasn't run (e.g. in a test slice).
     */
    @Transactional
    public Active active(String kind) {
        AnalysisSettings s = versions.findFirstByKindAndActiveTrueOrderByIdDesc(kind)
                .orElseGet(() -> versions.save(v0(kind)));
        return new Active(s, s.toProfile());
    }

    /**
     * Every version of {@code kind} that measures the same way as the active one
     * — including the active row itself.
     *
     * A game is out of date when it was measured by a different <i>ruler</i>, not
     * when it carries a different row id. Raising the depth and putting it back
     * leaves a third version whose numbers are identical to v0's; comparing ids
     * would declare the whole library outdated and offer to re-analyse it for no
     * change in output, deleting its drill cards on the way.
     */
    public Set<Long> engineEquivalentIds(String kind) {
        AnalysisSettings current = active(kind).settings();
        Set<Long> ids = new HashSet<>();
        for (AnalysisSettings v : versions.findByKindOrderByIdDesc(kind)) {
            if (v.sameEngineAs(current)) ids.add(v.getId());
        }
        return ids;
    }

    /** The row every game analysed before Settings existed is attributed to. */
    static AnalysisSettings v0(String kind) {
        AnalysisProfile p = AnalysisSettings.PRACTICE.equals(kind) ? AnalysisProfile.PRACTICE : AnalysisProfile.LIBRARY;
        return AnalysisSettings.builder()
                .kind(kind)
                .label(kind.toLowerCase(Locale.ROOT) + "-v0")
                .sweepMoveTimeMs(p.sweepMoveTimeMs())
                .multiPvDepth(p.multiPvDepth())
                .multiPvLines(p.multiPvLines())
                .maxExplanations(p.maxOllamaCalls())
                .active(true)
                .build();
    }

    public AppSettings appSettings() {
        return app.findById(AppSettings.SINGLETON_ID)
                .orElseGet(() -> AppSettings.builder().id(AppSettings.SINGLETON_ID).build());
    }

    // ── date ranges ──────────────────────────────────────────────────────────

    /**
     * The window a sync covers: the configured range if one is set, otherwise
     * the pre-Settings behaviour ({@code legacyMonths}: 1 for Sync Now, 3 for
     * Re-Sync), so an install that never opens Settings is unaffected.
     */
    public SyncWindow syncWindow(int legacyMonths) {
        AppSettings a = appSettings();
        if (a.getSyncFrom() != null && a.getSyncTo() != null) {
            return new SyncWindow(a.getSyncFrom(), a.getSyncTo());
        }
        return SyncWindow.lastMonths(legacyMonths, clock.today());
    }

    public boolean hasSyncRange() {
        AppSettings a = appSettings();
        return a.getSyncFrom() != null && a.getSyncTo() != null;
    }

    public boolean hasAnalysisRange() {
        AppSettings a = appSettings();
        return a.getAnalysisFrom() != null || a.getAnalysisTo() != null;
    }

    /** Whether a game falls inside the analysis range. Open bounds are unlimited. */
    public boolean inAnalysisRange(OffsetDateTime playedAt) {
        AppSettings a = appSettings();
        if (a.getAnalysisFrom() == null && a.getAnalysisTo() == null) return true;
        if (playedAt == null) return false;
        LocalDate d = clock.dateOf(playedAt);
        return (a.getAnalysisFrom() == null || !d.isBefore(a.getAnalysisFrom()))
                && (a.getAnalysisTo() == null || !d.isAfter(a.getAnalysisTo()));
    }

    // ── view / update ────────────────────────────────────────────────────────

    public View view() {
        AppSettings a = appSettings();
        return new View(a.getSyncFrom(), a.getSyncTo(), a.getAnalysisFrom(), a.getAnalysisTo(),
                toVersion(active(AnalysisSettings.LIBRARY).settings()),
                toVersion(active(AnalysisSettings.PRACTICE).settings()),
                bounds());
    }

    public static Bounds bounds() {
        return new Bounds(SWEEP_MIN, SWEEP_MAX, DEPTH_MIN, DEPTH_MAX, LINES_MIN, LINES_MAX,
                EXPLANATIONS_MAX, EARLIEST, PRACTICE_BUDGET_MS);
    }

    /** Thrown with every failed field at once, so the page can mark all of them. */
    public static class InvalidSettings extends RuntimeException {
        private final Map<String, String> errors;
        public InvalidSettings(Map<String, String> errors) {
            super("invalid settings: " + errors.keySet());
            this.errors = Map.copyOf(errors);
        }
        public Map<String, String> errors() { return errors; }
    }

    @Transactional
    public Saved update(Update u) {
        Map<String, String> errors = validate(u, clock.today());

        // The practice report has to arrive inside PlayImprove's polling window.
        // Only enforceable once there are timings to estimate from; until then
        // the page says the estimate isn't available rather than guessing.
        if (errors.isEmpty() && u.practice() != null) {
            KindEstimate pe = estimateKind(AnalysisSettings.PRACTICE, params(u.practice()));
            if (pe.measured() && pe.perGameMs() != null && pe.perGameMs() > PRACTICE_BUDGET_MS) {
                errors.put("practice_budget", "Estimated " + (pe.perGameMs() / 1000) + " s per practice game — "
                        + "over the " + (PRACTICE_BUDGET_MS / 1000) + " s the report waits for. "
                        + "Lower the depth or candidate moves.");
            }
        }
        if (!errors.isEmpty()) throw new InvalidSettings(errors);

        AppSettings a = appSettings();
        a.setSyncFrom(u.syncFrom());
        a.setSyncTo(u.syncTo());
        a.setAnalysisFrom(u.analysisFrom());
        a.setAnalysisTo(u.analysisTo());
        app.save(a);

        boolean lib = u.library() != null && saveIfChanged(AnalysisSettings.LIBRARY, u.library());
        boolean pra = u.practice() != null && saveIfChanged(AnalysisSettings.PRACTICE, u.practice());
        return new Saved(view(), lib, pra);
    }

    /**
     * A changed configuration becomes a new, active row; the previous one is
     * deactivated but kept, because games still point at it. An unchanged save
     * creates nothing — otherwise every click of Save would split the library
     * into "different" versions that measure identically.
     */
    private boolean saveIfChanged(String kind, EngineConfig c) {
        AnalysisSettings current = active(kind).settings();
        AnalysisSettings proposed = AnalysisSettings.builder()
                .kind(kind)
                .sweepMoveTimeMs(c.sweepMoveTimeMs())
                .multiPvDepth(c.multiPvDepth())
                .multiPvLines(c.multiPvLines())
                .maxExplanations(c.maxExplanations())
                .active(true)
                .build();
        if (proposed.sameEngineAs(current)) return false;

        current.setActive(false);
        versions.save(current);
        int n = versions.findByKindOrderByIdDesc(kind).size();
        proposed.setLabel(kind.toLowerCase(Locale.ROOT) + "-v" + n);
        versions.save(proposed);
        return true;
    }

    /** Pure, so it's testable without a database. Returns field → message. */
    static Map<String, String> validate(Update u, LocalDate today) {
        Map<String, String> e = new LinkedHashMap<>();
        if (u == null) {
            e.put("body", "Missing settings.");
            return e;
        }

        // A sync range costs API calls per month, so it's all or nothing: an
        // open-ended start would mean fetching every archive since 2007.
        if ((u.syncFrom() == null) != (u.syncTo() == null)) {
            e.put("sync_range", "Set both ends of the sync range, or clear both to use the default.");
        } else if (u.syncFrom() != null) {
            if (u.syncFrom().isAfter(u.syncTo())) e.put("sync_range", "The sync range starts after it ends.");
            else if (u.syncFrom().isBefore(EARLIEST)) e.put("sync_range", "Chess.com has no games before " + EARLIEST.getYear() + ".");
            else if (u.syncFrom().isAfter(today)) e.put("sync_range", "The sync range starts in the future.");
        }

        if (u.analysisFrom() != null && u.analysisTo() != null && u.analysisFrom().isAfter(u.analysisTo())) {
            e.put("analysis_range", "The analysis range starts after it ends.");
        }

        engine(e, "library", u.library());
        engine(e, "practice", u.practice());
        return e;
    }

    private static void engine(Map<String, String> e, String prefix, EngineConfig c) {
        if (c == null) return;   // omitted = leave that kind unchanged
        range(e, prefix + "_sweep_move_time_ms", c.sweepMoveTimeMs(), SWEEP_MIN, SWEEP_MAX, "Scan time per move", "ms");
        range(e, prefix + "_multi_pv_depth", c.multiPvDepth(), DEPTH_MIN, DEPTH_MAX, "Deep-check depth", "");
        range(e, prefix + "_multi_pv_lines", c.multiPvLines(), LINES_MIN, LINES_MAX, "Candidate moves", "");
        if (c.maxExplanations() != null && (c.maxExplanations() < 0 || c.maxExplanations() > EXPLANATIONS_MAX)) {
            e.put(prefix + "_max_explanations", "Written explanations must be 0–" + EXPLANATIONS_MAX + ", or all.");
        }
    }

    private static void range(Map<String, String> e, String key, Integer v, int min, int max, String name, String unit) {
        if (v == null) e.put(key, name + " is required.");
        else if (v < min || v > max) e.put(key, name + " must be " + min + "–" + max + (unit.isEmpty() ? "" : " " + unit) + ".");
    }

    // ── coverage ─────────────────────────────────────────────────────────────

    public Coverage coverage() {
        String user = props.chessCom().username();
        String zone = clock.zone().getId();
        Long activeLib = active(AnalysisSettings.LIBRARY).id();
        // Versions that measure the same way as the active one. A game under any
        // of them is already current; only a different ruler is out of date.
        Set<Long> sameRuler = engineEquivalentIds(AnalysisSettings.LIBRARY);

        Map<Long, String> labels = new HashMap<>();
        Map<Long, AnalysisSettings> byId = new HashMap<>();
        versions.findAll().forEach(v -> {
            labels.put(v.getId(), v.getLabel());
            byId.put(v.getId(), v);
        });

        // One grouped pass. Days are in the player's zone — the same calendar the
        // streaks use — so "games on the 21st" means their 21st.
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT (played_at AT TIME ZONE ?)::date AS day,
                       COALESCE(source, 'CHESS_COM')    AS src,
                       analysis_status                  AS status,
                       analysis_settings_id             AS sid,
                       COUNT(*)                         AS n
                FROM games
                WHERE username = ? AND played_at IS NOT NULL
                GROUP BY 1, 2, 3, 4
                """, zone, user);

        TreeMap<LocalDate, int[]> days = new TreeMap<>(Comparator.reverseOrder());   // [synced, analyzed, pending, failed]
        TreeMap<String, Map<Long, Integer>> monthSettings = new TreeMap<>();
        Map<Long, Integer> allSettings = new LinkedHashMap<>();
        int practicePlayed = 0, practiceAnalyzed = 0, outdated = 0;

        for (Map<String, Object> r : rows) {
            LocalDate day = ((java.sql.Date) r.get("day")).toLocalDate();
            String status = String.valueOf(r.get("status"));
            int n = ((Number) r.get("n")).intValue();
            Long sid = r.get("sid") == null ? null : ((Number) r.get("sid")).longValue();

            if ("PRACTICE".equals(r.get("src"))) {
                practicePlayed += n;
                if ("ANALYZED".equals(status)) practiceAnalyzed += n;
                continue;
            }

            int[] c = days.computeIfAbsent(day, d -> new int[4]);
            c[0] += n;
            switch (status) {
                case "ANALYZED" -> {
                    c[1] += n;
                    monthSettings.computeIfAbsent(YearMonth.from(day).toString(), m -> new LinkedHashMap<>())
                            .merge(sid, n, Integer::sum);
                    allSettings.merge(sid, n, Integer::sum);
                    if (!sameRuler.contains(sid) && inAnalysisRangeDate(day)) outdated += n;
                }
                case "PENDING", "ANALYZING" -> c[2] += n;
                case "FAILED" -> c[3] += n;
                default -> { }
            }
        }

        // Fold days into months, newest first.
        LinkedHashMap<String, List<DayCoverage>> byMonth = new LinkedHashMap<>();
        days.forEach((d, c) -> byMonth.computeIfAbsent(YearMonth.from(d).toString(), m -> new ArrayList<>())
                .add(new DayCoverage(d, c[0], c[1], c[2], c[3])));

        List<MonthCoverage> months = new ArrayList<>();
        int synced = 0, analyzed = 0, pending = 0, failed = 0;
        for (var m : byMonth.entrySet()) {
            int s = 0, a = 0, p = 0, f = 0;
            for (DayCoverage d : m.getValue()) {
                s += d.synced(); a += d.analyzed(); p += d.pending(); f += d.failed();
            }
            synced += s; analyzed += a; pending += p; failed += f;
            months.add(new MonthCoverage(m.getKey(), s, a, p, f,
                    counts(monthSettings.getOrDefault(m.getKey(), Map.of()), labels), m.getValue()));
        }

        LocalDate first = days.isEmpty() ? null : days.lastKey();
        LocalDate last = days.isEmpty() ? null : days.firstKey();
        List<SettingsCount> with = counts(allSettings, labels);

        // "Mixed" means measured by more than one ruler, not labelled with more
        // than one version. Two versions holding identical engine numbers produce
        // comparable results, so warning about them would be noise.
        List<AnalysisSettings> rulers = new ArrayList<>();
        boolean unknownRuler = false;
        for (Long sid : allSettings.keySet()) {
            AnalysisSettings v = sid == null ? null : byId.get(sid);
            if (v == null) unknownRuler = true;
            else if (rulers.stream().noneMatch(v::sameEngineAs)) rulers.add(v);
        }
        boolean mixed = rulers.size() + (unknownRuler ? 1 : 0) > 1;

        return new Coverage(first, last, synced, analyzed, pending, failed, months,
                new PracticeCoverage(practicePlayed, practiceAnalyzed), activeLib,
                mixed, with, outdated);
    }

    private boolean inAnalysisRangeDate(LocalDate d) {
        AppSettings a = appSettings();
        return (a.getAnalysisFrom() == null || !d.isBefore(a.getAnalysisFrom()))
                && (a.getAnalysisTo() == null || !d.isAfter(a.getAnalysisTo()));
    }

    private static List<SettingsCount> counts(Map<Long, Integer> m, Map<Long, String> labels) {
        List<SettingsCount> out = new ArrayList<>();
        m.forEach((sid, n) -> out.add(new SettingsCount(sid, sid == null ? "unrecorded" : labels.getOrDefault(sid, "v?"), n)));
        out.sort(Comparator.comparingInt(SettingsCount::games).reversed());
        return out;
    }

    // ── estimate ─────────────────────────────────────────────────────────────

    public Estimate estimate(EstimateRequest r) {
        AnalysisSettings lib = active(AnalysisSettings.LIBRARY).settings();
        AnalysisSettings pra = active(AnalysisSettings.PRACTICE).settings();

        KindEstimate le = estimateKind(AnalysisSettings.LIBRARY,
                r != null && r.library() != null ? params(r.library()) : params(lib));
        KindEstimate pe = estimateKind(AnalysisSettings.PRACTICE,
                r != null && r.practice() != null ? params(r.practice()) : params(pra));

        int inRange = gamesInAnalysisRange();
        Long total = le.measured() && le.perGameMs() != null ? le.perGameMs() * inRange : null;
        Boolean within = pe.measured() && pe.perGameMs() != null ? pe.perGameMs() <= PRACTICE_BUDGET_MS : null;
        return new Estimate(le, inRange, total, pe, PRACTICE_BUDGET_MS, within);
    }

    /**
     * Estimates from the active version's timings if it has enough of them,
     * otherwise from the most recent version that does. Practice falls back to
     * library timings: the per-position and per-move costs belong to this
     * machine's engine and model, not to the kind of game.
     */
    KindEstimate estimateKind(String kind, AnalysisCostModel.Params proposed) {
        List<String> kinds = AnalysisSettings.PRACTICE.equals(kind)
                ? List.of(AnalysisSettings.PRACTICE, AnalysisSettings.LIBRARY)
                : List.of(AnalysisSettings.LIBRARY);
        for (String k : kinds) {
            for (AnalysisSettings v : versions.findByKindOrderByIdDesc(k)) {
                List<AnalysisCostModel.Sample> samples = timings.findTop50BySettingsIdOrderByCreatedAtDesc(v.getId())
                        .stream().map(SettingsService::sample).toList();
                Optional<AnalysisCostModel.Rates> rates = AnalysisCostModel.rates(samples, params(v));
                if (rates.isPresent()) {
                    return new KindEstimate(true, rates.get().samples(), v.getLabel(),
                            AnalysisCostModel.perGameMs(rates.get(), proposed),
                            rates.get().explanationsMeasured());
                }
            }
        }
        return new KindEstimate(false, 0, null, null, false);
    }

    private int gamesInAnalysisRange() {
        String user = props.chessCom().username();
        AppSettings a = appSettings();
        String zone = clock.zone().getId();
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM games
                WHERE username = ? AND COALESCE(source, 'CHESS_COM') = 'CHESS_COM'
                  AND (?::date IS NULL OR (played_at AT TIME ZONE ?)::date >= ?::date)
                  AND (?::date IS NULL OR (played_at AT TIME ZONE ?)::date <= ?::date)
                """, Integer.class, user,
                a.getAnalysisFrom(), zone, a.getAnalysisFrom(),
                a.getAnalysisTo(), zone, a.getAnalysisTo());
        return n == null ? 0 : n;
    }

    private static AnalysisCostModel.Sample sample(AnalysisTiming t) {
        return new AnalysisCostModel.Sample(t.getPlies(), t.getCandidates(), t.getExplanations(),
                t.getSweepMs(), t.getEnrichMs(), t.getExplainMs());
    }

    static AnalysisCostModel.Params params(AnalysisSettings s) {
        return new AnalysisCostModel.Params(s.getSweepMoveTimeMs(), s.getMultiPvDepth(),
                s.getMultiPvLines(), s.getMaxExplanations());
    }

    static AnalysisCostModel.Params params(EngineConfig c) {
        return new AnalysisCostModel.Params(
                c.sweepMoveTimeMs() == null ? 0 : c.sweepMoveTimeMs(),
                c.multiPvDepth() == null ? 0 : c.multiPvDepth(),
                c.multiPvLines() == null ? 1 : c.multiPvLines(),
                c.maxExplanations());
    }

    static EngineVersion toVersion(AnalysisSettings s) {
        return new EngineVersion(s.getId(), s.getLabel(), s.getSweepMoveTimeMs(), s.getMultiPvDepth(),
                s.getMultiPvLines(), s.getMaxExplanations(), s.getCreatedAt());
    }
}
