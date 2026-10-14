package com.praxis.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catch-all that sends client-side routes to index.html. When it also
 * matched "index.html", every page of the bundled JAR forwarded to itself until
 * the stack overflowed — found the first time the Docker image served the UI.
 */
@DisplayName("SPA fallback routing")
class SpaControllerTest {

    private static final Pattern SPA = Pattern.compile(SpaController.SPA_EXCLUSIONS);

    @Test
    void clientRoutesGoToTheApp() {
        assertThat(SPA.matcher("progress").matches()).isTrue();
        assertThat(SPA.matcher("settings").matches()).isTrue();
        assertThat(SPA.matcher("games").matches()).isTrue();
    }

    @Test
    void filesAreLeftToTheStaticHandlerSoIndexHtmlCannotForwardToItself() {
        assertThat(SPA.matcher("index.html").matches()).isFalse();
        assertThat(SPA.matcher("favicon.ico").matches()).isFalse();
        assertThat(SPA.matcher("robots.txt").matches()).isFalse();
    }

    @Test
    void apiAndInfrastructurePathsAreNeverIntercepted() {
        assertThat(SPA.matcher("api").matches()).isFalse();
        assertThat(SPA.matcher("assets").matches()).isFalse();
        assertThat(SPA.matcher("actuator").matches()).isFalse();
        assertThat(SPA.matcher("swagger-ui").matches()).isFalse();
    }
}
