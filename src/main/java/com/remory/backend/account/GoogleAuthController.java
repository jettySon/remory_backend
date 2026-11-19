package com.remory.backend.account;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/google")
public class GoogleAuthController {

    private static final String BROWSER_COOKIE = "remory_google_browser";
    private static final Pattern BROWSER_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final SecureRandom random = new SecureRandom();
    private final AccountService accounts;
    private final GoogleIdentityVerifier google;

    public GoogleAuthController(AccountService accounts, GoogleIdentityVerifier google) {
        this.accounts = accounts;
        this.google = google;
    }

    @GetMapping("/challenge")
    ResponseEntity<Challenge> challenge(
            @CookieValue(name = BROWSER_COOKIE, required = false) String browser,
            @RequestParam(defaultValue = "login") String purpose,
            HttpServletRequest request) {
        if (!Set.of("login", "delete").contains(purpose)) {
            throw AccountService.googleError(HttpStatus.BAD_REQUEST, "GOOGLE_INVALID");
        }
        if (browser == null || !BROWSER_TOKEN.matcher(browser).matches()) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            browser = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
        String nonce = accounts.createGoogleChallenge(browser, purpose);
        var cookie = ResponseCookie.from(BROWSER_COOKIE, browser).httpOnly(true).secure(request.isSecure())
                .sameSite("Lax").path("/api/v1/auth/google").maxAge(Duration.ofHours(1)).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new Challenge(google.clientId(), nonce));
    }

    @PostMapping
    ResponseEntity<AccountService.Session> login(
            @CookieValue(name = BROWSER_COOKIE, required = false) String browser,
            @Valid @RequestBody GoogleLogin request) {
        requireBrowser(browser);
        var identity = google.verify(request.credential(), request.nonce());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(accounts.loginWithGoogle(identity, browser, request.password()));
    }

    @PostMapping("/me/delete")
    ResponseEntity<Void> deleteSignedIn(
            @CookieValue(name = BROWSER_COOKIE, required = false) String browser,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody GoogleCredential request) {
        accounts.authenticate(authorization);
        requireBrowser(browser);
        accounts.deleteWithGoogle(authorization, google.verify(request.credential(), request.nonce()), browser);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/account-deletion")
    ResponseEntity<Account> deleteFromWeb(
            @CookieValue(name = BROWSER_COOKIE, required = false) String browser,
            @Valid @RequestBody GoogleCredential request) {
        requireBrowser(browser);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(accounts.deleteWithGoogle(null, google.verify(request.credential(), request.nonce()), browser));
    }

    private static void requireBrowser(String browser) {
        if (browser == null || !BROWSER_TOKEN.matcher(browser).matches()) {
            throw AccountService.googleError(HttpStatus.UNAUTHORIZED, "GOOGLE_RESTART");
        }
    }

    record Challenge(String clientId, String nonce) {}
    record GoogleCredential(@NotBlank @Size(max = 8192) String credential, @NotBlank @Size(min = 43, max = 43) String nonce) {}
    record GoogleLogin(@NotBlank @Size(max = 8192) String credential,
                       @NotBlank @Size(min = 43, max = 43) String nonce,
                       @Size(max = 72) String password) {}
}
