package com.remory.backend.account;

import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SessionCookies {
    static final String NAME = "remory_refresh";
    private static final String PATH = "/api/v1/auth";

    String issue(AccountService.Session session, HttpServletRequest request) {
        Duration age = Duration.between(Instant.now(), session.refreshExpiresAt());
        return cookie(session.refreshToken(), age.isNegative() ? Duration.ZERO : age, request).toString();
    }

    String clear(HttpServletRequest request) {
        return cookie("", Duration.ZERO, request).toString();
    }

    private ResponseCookie cookie(String value, Duration age, HttpServletRequest request) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(request.isSecure())
                .sameSite("Lax").path(PATH).maxAge(age).build();
    }

    static void requireBrowserRequest(HttpServletRequest request) {
        // A cross-origin form cannot add this header; untrusted CORS origins are not enabled.
        if (!"web".equals(request.getHeader("X-Remory-Client"))
                || "cross-site".equals(request.getHeader("Sec-Fetch-Site"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SESSION_ORIGIN_REJECTED");
        }
    }
}
