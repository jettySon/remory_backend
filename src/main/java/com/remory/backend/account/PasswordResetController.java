package com.remory.backend.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/password-reset")
public class PasswordResetController {
    private final PasswordResetService resets;

    public PasswordResetController(PasswordResetService resets) {
        this.resets = resets;
    }

    @PostMapping("/request")
    ResponseEntity<PasswordResetService.RequestChallenge> request(@Valid @RequestBody ResetRequest request, HttpServletRequest servlet) {
        var challenge = resets.request(request.email(), request.locale(), servlet.getRemoteAddr());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(challenge);
    }

    @PostMapping("/verify")
    ResponseEntity<PasswordResetService.Verification> verify(@Valid @RequestBody CodeVerification request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(resets.verify(request.challengeId(), request.code()));
    }

    @PostMapping("/confirm")
    ResponseEntity<Void> confirm(@Valid @RequestBody Confirmation request) {
        resets.confirm(request.token(), request.password());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    record ResetRequest(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "ko|en|ja|zh-CN") String locale) {}

    record CodeVerification(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String challengeId,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}

    record Confirmation(@NotBlank @Size(max = 128) String token,
            @NotBlank @Size(min = 8, max = 72) String password) {}
}
