package com.remory.backend.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/signup")
    ResponseEntity<AccountService.Session> signup(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(accounts.signup(request.email(), request.password()));
    }

    @PostMapping("/login")
    ResponseEntity<AccountService.Session> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(accounts.login(request.email(), request.password()));
    }

    @GetMapping("/me")
    ResponseEntity<Account> me(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.authenticate(token));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token) {
        accounts.logout(token);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/delete")
    ResponseEntity<Void> deleteSignedInAccount(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String token,
            @Valid @RequestBody ConfirmDeletion request) {
        accounts.deleteSignedInAccount(token, request.password());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/account-deletion")
    ResponseEntity<Void> deleteAccountFromWeb(@Valid @RequestBody LoginRequest request) {
        accounts.deleteAccountByCredentials(request.email(), request.password());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    record ConfirmDeletion(@NotBlank @Size(max = 72) String password) {}

    record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password
    ) {}
}
