package com.praxis.api;

import com.praxis.dto.SettingsDto.*;
import com.praxis.service.settings.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The Settings page: sync and analysis date ranges, versioned engine settings,
 * the coverage view and measured time estimates.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public View get() {
        return settings.view();
    }

    /**
     * 200 with the saved settings, or 400 with every invalid field at once.
     * Out-of-range values are rejected, never silently clamped: a clamped depth
     * would be a different ruler from the one the user asked for, recorded on
     * every game analysed with it.
     */
    @PutMapping
    public ResponseEntity<?> update(@RequestBody Update body) {
        try {
            return ResponseEntity.ok(settings.update(body));
        } catch (SettingsService.InvalidSettings e) {
            return ResponseEntity.badRequest().body(new Invalid(e.errors()));
        }
    }

    @GetMapping("/coverage")
    public Coverage coverage() {
        return settings.coverage();
    }

    /** POST because it takes a proposed configuration as a body; it changes nothing. */
    @PostMapping("/estimate")
    public Estimate estimate(@RequestBody(required = false) EstimateRequest body) {
        return settings.estimate(body);
    }
}
