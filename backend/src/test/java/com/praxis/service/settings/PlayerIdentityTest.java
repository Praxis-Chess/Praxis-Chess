package com.praxis.service.settings;

import com.praxis.config.AppProperties;
import com.praxis.domain.AppSettings;
import com.praxis.repository.AppSettingsRepository;
import com.praxis.repository.GameRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Whose games the install reads. The username moved from application.yml to
 * Settings for the Docker install; an install configured the old way must keep
 * working with nothing stored, and a stored name must win once there is one.
 */
@DisplayName("Player identity: Settings first, config as fallback")
class PlayerIdentityTest {

    private final AppSettingsRepository settings = mock(AppSettingsRepository.class);
    private final GameRepository games = mock(GameRepository.class);

    private PlayerIdentity identity(String configured) {
        AppProperties props = new AppProperties(null, new AppProperties.ChessCom(configured), null, null, null, null);
        return new PlayerIdentity(settings, games, props);
    }

    private void stored(String username) {
        when(settings.findById(AppSettings.SINGLETON_ID)).thenReturn(Optional.of(
                AppSettings.builder().id(AppSettings.SINGLETON_ID).chessComUsername(username).build()));
    }

    @Test
    void anInstallConfiguredTheOldWayKeepsItsUsername() {
        stored(null);
        PlayerIdentity.Current c = identity("magnus").current();
        assertThat(c.username()).isEqualTo("magnus");
        assertThat(c.source()).isEqualTo(PlayerIdentity.Source.CONFIG);
    }

    @Test
    void aNameSavedInSettingsWinsOverConfig() {
        stored("hikaru");
        PlayerIdentity.Current c = identity("magnus").current();
        assertThat(c.username()).isEqualTo("hikaru");
        assertThat(c.source()).isEqualTo(PlayerIdentity.Source.SETTINGS);
    }

    @Test
    void theExampleConfigPlaceholderCountsAsNoUsername() {
        when(settings.findById(any())).thenReturn(Optional.empty());
        PlayerIdentity id = identity("YOUR_CHESS_COM_USERNAME_HERE");
        assertThat(id.username()).isEmpty();
        assertThat(id.isSet()).isFalse();
        assertThat(id.current().source()).isEqualTo(PlayerIdentity.Source.NONE);
    }

    @Test
    void aSavedNameTakesTheSpellingItsGamesWereStoredUnder() {
        // Games are stored under the exact spelling they were synced with, and
        // every query matches it exactly: "Magnus" must not hide "magnus"'s library.
        when(settings.findById(any())).thenReturn(Optional.empty());
        when(games.findStoredSpellings("Magnus")).thenReturn(List.of("magnus"));
        PlayerIdentity id = identity(null);

        assertThat(id.save("  Magnus ").username()).isEqualTo("magnus");
        ArgumentCaptor<AppSettings> saved = ArgumentCaptor.forClass(AppSettings.class);
        verify(settings).save(saved.capture());
        assertThat(saved.getValue().getChessComUsername()).isEqualTo("magnus");
        assertThat(id.username()).isEqualTo("magnus");
    }

    @Test
    void aNewPlayerIsStoredAsTyped() {
        when(settings.findById(any())).thenReturn(Optional.empty());
        when(games.findStoredSpellings("Fresh_Start")).thenReturn(List.of());
        assertThat(identity(null).save("Fresh_Start").username()).isEqualTo("Fresh_Start");
    }

    @Test
    void namesThatCannotBeChessComUsernamesAreRejected() {
        assertThat(PlayerIdentity.invalidReason("")).isNotNull();
        assertThat(PlayerIdentity.invalidReason("a b")).isNotNull();
        assertThat(PlayerIdentity.invalidReason("../admin")).isNotNull();
        assertThat(PlayerIdentity.invalidReason("good-name_42")).isNull();
        assertThatThrownBy(() -> identity(null).save("x/y")).isInstanceOf(IllegalArgumentException.class);
        verify(settings, never()).save(any());
    }

    @Test
    void anUnreachableDatabaseFallsBackToConfigWithoutCachingIt() {
        when(settings.findById(any())).thenThrow(new RuntimeException("connection refused")).thenReturn(Optional.of(
                AppSettings.builder().id(AppSettings.SINGLETON_ID).chessComUsername("hikaru").build()));
        PlayerIdentity id = identity("magnus");
        assertThat(id.username()).isEqualTo("magnus");
        assertThat(id.username()).isEqualTo("hikaru");
    }
}
