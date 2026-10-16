package com.praxis.api;

import com.praxis.dto.BackupDto.Overview;
import com.praxis.dto.BackupDto.Result;
import com.praxis.service.backup.BackupService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** Settings → Backups: what the backup service has done, and "Back up now". */
@RestController
@RequestMapping("/api/backups")
public class BackupController {

    private final BackupService backups;

    public BackupController(BackupService backups) {
        this.backups = backups;
    }

    @GetMapping
    public Overview overview() {
        return backups.overview();
    }

    /** 200 with the result, or 503 with the reason when no snapshot could be taken. */
    @PostMapping
    public ResponseEntity<Result> backUpNow() {
        Result r = backups.snapshot("manual", Duration.ofMinutes(5));
        return r.ok() ? ResponseEntity.ok(r) : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(r);
    }
}
