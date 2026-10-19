package com.praxis.service.setup;

import com.praxis.ai.Feature;
import com.praxis.ai.ModelRouter;
import com.praxis.config.AppProperties;
import com.praxis.dto.SetupDto.Check;
import com.praxis.dto.SetupDto.State;
import com.praxis.dto.SetupDto.Status;
import com.praxis.service.analysis.StockfishService;
import com.praxis.service.backup.BackupService;
import com.praxis.service.settings.SettingsService;
import com.praxis.domain.AnalysisSettings;
import com.praxis.service.settings.PlayerIdentity;
import com.praxis.service.voice.TtsClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Setup checklist. It replaces "read the server log" as the way a new
 * install finds out what is missing, so each failure must name its fix.
 */
@DisplayName("Setup checklist")
class SetupStatusServiceTest {

    // Nothing listens on port 1: Ollama is "not reachable" without a network wait.
    private static final AppProperties.Ollama OLLAMA = new AppProperties.Ollama(
            "http://127.0.0.1:1", "qwen2.5:7b", "qwen2.5:7b", "qwen2.5:7b", "qwen3:4b-instruct", "praxis-grid-2b-r3");

    private SetupStatusService service(boolean dbUp, boolean engineUp, String username) throws SQLException {
        AppProperties props = new AppProperties(OLLAMA, new AppProperties.ChessCom(username),
                new AppProperties.Stockfish("/usr/local/bin/stockfish"),
                new AppProperties.Tts(null, false), null, null);

        DataSource ds = mock(DataSource.class);
        if (!dbUp) when(ds.getConnection()).thenThrow(new SQLException("connection refused"));
        else when(ds.getConnection()).thenReturn(mock(java.sql.Connection.class, org.mockito.Answers.RETURNS_DEEP_STUBS));

        StockfishService engine = mock(StockfishService.class);
        when(engine.isAvailable()).thenReturn(engineUp);
        when(engine.engineName()).thenReturn(engineUp ? "Stockfish 19" : null);

        PlayerIdentity player = mock(PlayerIdentity.class);
        when(player.current()).thenReturn(username == null
                ? new PlayerIdentity.Current("", PlayerIdentity.Source.NONE)
                : new PlayerIdentity.Current(username, PlayerIdentity.Source.SETTINGS));

        ModelRouter router = mock(ModelRouter.class);
        when(router.forFeature(Feature.EXPLANATIONS)).thenReturn(new ModelRouter.Route(Feature.EXPLANATIONS, null, "qwen2.5:7b", false));
        when(router.forFeature(Feature.REPORTS)).thenReturn(new ModelRouter.Route(Feature.REPORTS, null, "qwen2.5:7b", false));
        when(router.forFeature(Feature.PRAX)).thenReturn(new ModelRouter.Route(Feature.PRAX, null, "qwen3:4b-instruct", false));

        BackupService backups = mock(BackupService.class);
        when(backups.check()).thenReturn(new Check("backups", "Backups", State.WARN, "No backups yet", "docker compose up -d backup"));

        // Explanations still on (3 per game), as on an install before Phase 12.
        SettingsService settings = mock(SettingsService.class);
        var v0 = AnalysisSettings.builder().kind(AnalysisSettings.LIBRARY).label("library-v0")
                .sweepMoveTimeMs(100).multiPvDepth(18).multiPvLines(3).maxExplanations(3).active(true).build();
        when(settings.active(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new SettingsService.Active(v0, v0.toProfile()));

        return new SetupStatusService(ds, props, engine, player, router, mock(TtsClient.class), backups, settings);
    }

    private static Check check(Status s, String key) {
        return s.checks().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void aFreshInstallIsNotReadyUntilAUsernameIsEntered() throws Exception {
        Status s = service(true, true, null).status();
        assertThat(s.ready()).isFalse();
        assertThat(check(s, "username").state()).isEqualTo(State.FAIL);
        assertThat(check(s, "username").fix()).contains("Settings");
        assertThat(s.stockfish()).isEqualTo("Stockfish 19");
    }

    @Test
    void readyMeansSyncAndEngineAnalysisWorkEvenWhileModelsAreMissing() throws Exception {
        Status s = service(true, true, "magnus").status();
        assertThat(s.ready()).isTrue();
        assertThat(check(s, "ollama").state()).isEqualTo(State.WARN);
    }

    @Test
    void missingBackupsWarnButNeverMakeTheInstallNotReady() throws Exception {
        Status s = service(true, true, "magnus").status();
        assertThat(check(s, "backups").state()).isEqualTo(State.WARN);
        assertThat(s.ready()).isTrue();
    }

    @Test
    void oneModelServingTwoFeaturesIsListedOnceWithBothRoles() throws Exception {
        Status s = service(true, true, "magnus").status();
        assertThat(s.models()).extracting(m -> m.name())
                .containsExactly("qwen2.5:7b", "qwen3:4b-instruct", "praxis-grid-2b-r3");
        assertThat(s.models().get(0).role()).contains("explanations").containsIgnoringCase("report");
        assertThat(s.models()).allMatch(m -> m.state().equals("missing"));
    }

    @Test
    void aMissingEngineOrDatabaseFailsWithAFix() throws Exception {
        Status s = service(false, false, "magnus").status();
        assertThat(s.ready()).isFalse();
        assertThat(check(s, "database").state()).isEqualTo(State.FAIL);
        assertThat(check(s, "database").fix()).isNotBlank();
        assertThat(check(s, "stockfish").state()).isEqualTo(State.FAIL);
        assertThat(check(s, "stockfish").fix()).isNotBlank();
    }

    @Test
    void optionalServicesThatAreOffAreSkippedNotFailed() throws Exception {
        Status s = service(true, true, "magnus").status();
        assertThat(check(s, "voice").state()).isEqualTo(State.SKIP);
        assertThat(check(s, "web").state()).isEqualTo(State.SKIP);
    }

    @Test
    void ollamaListsACreatedModelWithItsImplicitTag() {
        assertThat(SetupStatusService.normalise("praxis-grid-2b-r3")).isEqualTo("praxis-grid-2b-r3:latest");
        assertThat(SetupStatusService.normalise("qwen2.5:7b")).isEqualTo("qwen2.5:7b");
        assertThat(SetupStatusService.normalise(" Qwen3:4B-Instruct ")).isEqualTo("qwen3:4b-instruct");
    }
}
