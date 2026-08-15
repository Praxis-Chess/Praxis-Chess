package com.praxis.api;

import com.praxis.dto.TodayInsightDto;
import com.praxis.service.ai.TodayInsightService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Today-page endpoint: returns a single structured training recommendation.
 *
 * GET /api/today  → TodayInsightDto
 *
 * Always returns 200. LLM failures fall back to a template-generated response
 * so the page never shows an error for this card.
 */
@RestController
@RequestMapping("/api/today")
public class TodayController {

    private final TodayInsightService insightService;

    public TodayController(TodayInsightService insightService) {
        this.insightService = insightService;
    }

    @GetMapping
    public ResponseEntity<TodayInsightDto> getToday() {
        return ResponseEntity.ok(insightService.generate());
    }
}
