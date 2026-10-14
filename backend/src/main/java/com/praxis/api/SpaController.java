package com.praxis.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards all React Router client-side paths to index.html.
 *
 * The negative lookahead in the path regex excludes:
 *   api, assets, actuator, v3 (OpenAPI), swagger-ui
 * so static files and REST endpoints are never intercepted.
 * Spring MVC matches @RestController routes before this catch-all anyway,
 * but the regex is a second line of defence for nested routes.
 *
 * A first segment with a dot is a FILE (index.html, favicon.ico) and is left
 * to the static resource handler. Without that, "/" forwarded to /index.html,
 * this same pattern matched "index.html" and forwarded it again, forever: a
 * StackOverflowError on every page of the bundled JAR. Development never
 * showed it, because there Vite serves the UI; the Docker image did.
 */
@Controller
public class SpaController {

    static final String SPA_EXCLUSIONS =
            "^(?!api|assets|actuator|v3|swagger-ui)[^.]*$";

    @GetMapping("/")
    public String root() {
        return "forward:/index.html";
    }

    @GetMapping("/{path:" + SPA_EXCLUSIONS + "}")
    public String spa() {
        return "forward:/index.html";
    }

    @GetMapping("/{path:" + SPA_EXCLUSIONS + "}/**")
    public String spaDeep() {
        return "forward:/index.html";
    }
}
