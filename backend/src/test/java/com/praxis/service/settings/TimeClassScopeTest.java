package com.praxis.service.settings;

import com.praxis.dto.SettingsDto.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sync fetches every time class; analysis takes only the chosen ones, rapid by
 * default, so bullet's time-scramble blunders don't drown out rapid weaknesses.
 */
@DisplayName("Analysed time classes")
class TimeClassScopeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    @Test
    @DisplayName("rapid only until something else is chosen")
    void defaultsToRapid() {
        assertThat(SettingsService.parseTimeClasses(null)).containsExactly("rapid");
        assertThat(SettingsService.parseTimeClasses("")).containsExactly("rapid");
    }

    @Test
    @DisplayName("a saved choice reads back in speed order, unknown names dropped")
    void readsTheSavedChoice() {
        assertThat(SettingsService.parseTimeClasses("rapid,bullet")).containsExactly("bullet", "rapid");
        assertThat(SettingsService.parseTimeClasses(" Blitz , chess960 ")).containsExactly("blitz");
        assertThat(SettingsService.parseTimeClasses("chess960")).containsExactly("rapid");
    }

    @Test
    @DisplayName("an empty or unknown choice is refused, not saved")
    void validatesTheChoice() {
        assertThat(SettingsService.validate(update(List.of()), TODAY)).containsKey("analysis_time_classes");
        assertThat(SettingsService.validate(update(List.of("rapid", "hyperbullet")), TODAY))
                .containsKey("analysis_time_classes");
        assertThat(SettingsService.validate(update(List.of("rapid", "bullet")), TODAY)).isEmpty();
    }

    @Test
    @DisplayName("an older client that sends no choice leaves it alone")
    void absentMeansUnchanged() {
        assertThat(SettingsService.validate(new Update(null, null, null, null, null, null), TODAY)).isEmpty();
    }

    private static Update update(List<String> classes) {
        return new Update(null, null, null, null, classes, null, null);
    }
}
