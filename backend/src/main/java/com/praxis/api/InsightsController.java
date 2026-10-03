package com.praxis.api;

import com.praxis.dto.InsightsDto;
import com.praxis.service.InsightsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/insights")
public class InsightsController {

    private final InsightsService insightsService;

    public InsightsController(InsightsService insightsService) {
        this.insightsService = insightsService;
    }

    /**
     * @param timeClass a Chess.com time class ("rapid", "bullet", …), "all" for every
     *                  game, or absent for the default (the analysed time class).
     */
    @GetMapping
    public ResponseEntity<InsightsDto> getInsights(@RequestParam(name = "time_class", required = false) String timeClass) {
        if (timeClass == null || timeClass.isBlank()) return ResponseEntity.ok(insightsService.computeDefault());
        return ResponseEntity.ok(insightsService.compute("all".equals(timeClass) ? null : timeClass));
    }
}
