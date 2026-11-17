package com.remory.backend.study;

import com.remory.backend.account.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/study")
public class StudyController {

    private final AccountService accounts;
    private final StudyService study;

    public StudyController(AccountService accounts, StudyService study) {
        this.accounts = accounts;
        this.study = study;
    }

    @GetMapping
    ResponseEntity<StudyService.Snapshot> read(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token) {
        var account = accounts.authenticate(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(study.read(account.id()));
    }

    @PutMapping
    ResponseEntity<StudyService.Snapshot> replace(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
            @Valid @RequestBody ReplaceRequest request) {
        var account = accounts.authenticate(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(study.replace(account.id(), request.revision(), request.data()));
    }

    @PostMapping("/import")
    ResponseEntity<StudyService.Snapshot> importGuest(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
            @Valid @RequestBody StudyData guest) {
        var account = accounts.authenticate(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(study.importGuest(account.id(), guest));
    }

    record ReplaceRequest(@NotNull @PositiveOrZero Long revision, @NotNull @Valid StudyData data) {}
}
