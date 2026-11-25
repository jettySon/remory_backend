package com.remory.backend.account;

import java.util.Map;

import com.remory.backend.study.StudyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class SessionController {
    private final SessionService sessions;
    private final SessionCookies cookies;
    private final StudyService study;

    public SessionController(SessionService sessions, SessionCookies cookies, StudyService study) {
        this.sessions = sessions;
        this.cookies = cookies;
        this.study = study;
    }

    @GetMapping("/review-reminder")
    ResponseEntity<Map<String, Boolean>> reviewReminder(
            @CookieValue(name = SessionCookies.NAME, required = false) String refresh,
            HttpServletRequest request) {
        SessionCookies.requireBrowserRequest(request);
        boolean due = study.hasDueCards(sessions.accountForReminder(refresh));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("due", due));
    }

    @PostMapping("/refresh")
    ResponseEntity<AccountService.Session> refresh(
            @CookieValue(name = SessionCookies.NAME, required = false) String refresh,
            HttpServletRequest request) {
        SessionCookies.requireBrowserRequest(request);
        return response(sessions.refresh(refresh), request);
    }

    @PostMapping("/session")
    ResponseEntity<AccountService.Session> upgradeLegacy(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) {
        SessionCookies.requireBrowserRequest(request);
        return response(sessions.upgradeLegacy(authorization), request);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            @CookieValue(name = SessionCookies.NAME, required = false) String refresh,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) {
        SessionCookies.requireBrowserRequest(request);
        sessions.logout(refresh, authorization);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.clear(request)).build();
    }

    private ResponseEntity<AccountService.Session> response(AccountService.Session session, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.issue(session, request)).body(session);
    }

    @ExceptionHandler(SessionService.Rejected.class)
    ResponseEntity<ProblemDetail> rejected(SessionService.Rejected failure, HttpServletRequest request) {
        var response = ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore());
        // A concurrent refresh must not erase another response's new cookie.
        if (failure.getStatusCode() == HttpStatus.UNAUTHORIZED) response.header(HttpHeaders.SET_COOKIE, cookies.clear(request));
        return response.body(failure.getBody());
    }
}
