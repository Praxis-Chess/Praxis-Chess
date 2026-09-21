package com.praxis.api;

import com.praxis.config.AppProperties;
import com.praxis.dto.PracticeStreakDto;
import com.praxis.service.practice.PracticeStreakService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/practice")
public class PracticeController {

    private final PracticeStreakService streakService;
    private final AppProperties appProperties;

    public PracticeController(PracticeStreakService streakService, AppProperties appProperties) {
        this.streakService = streakService;
        this.appProperties = appProperties;
    }

    @GetMapping("/streak")
    public ResponseEntity<PracticeStreakDto> streak() {
        return ResponseEntity.ok(streakService.compute(appProperties.chessCom().username()));
    }
}
