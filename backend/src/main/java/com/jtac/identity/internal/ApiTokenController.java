package com.jtac.identity.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Personal API tokens of the signed-in user. Token management itself requires a session. */
@RestController
@RequestMapping(ApiPaths.V1 + "/me/tokens")
class ApiTokenController {

    private final ApiTokenService tokens;

    ApiTokenController(ApiTokenService tokens) {
        this.tokens = tokens;
    }

    @GetMapping
    List<ApiTokenService.TokenInfo> list(CurrentUser user) {
        return tokens.list(user.id());
    }

    @PostMapping
    ResponseEntity<ApiTokenService.CreatedToken> create(CurrentUser user, @Valid @RequestBody CreateToken body) {
        requireSession(user);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(tokens.create(user.id(), body.name(), body.scopes(), body.organizationId(), body.expiresAt()));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> revoke(CurrentUser user, @PathVariable UUID id) {
        requireSession(user);
        tokens.revoke(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    /** A token must not be able to mint or revoke tokens. */
    private static void requireSession(CurrentUser user) {
        if (user.viaApiToken()) {
            throw ApiErrors.forbidden("Manage API tokens from a signed-in session.");
        }
    }

    record CreateToken(@NotBlank @Size(max = 80) String name, @NotEmpty List<String> scopes, UUID organizationId,
            Instant expiresAt) {
    }
}
