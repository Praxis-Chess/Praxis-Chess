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
 * Always returns 200. Built from counts alone (TodayInsightService), with no
 * model call, so the card is instant and every number in it is real.
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
