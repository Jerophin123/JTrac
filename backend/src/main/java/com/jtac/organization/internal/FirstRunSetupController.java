package com.jtac.organization.internal;

import com.jtac.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Public first-run setup: open only while no account exists (see {@link FirstRunSetup}). */
@RestController
class FirstRunSetupController {

    private static final String PATH = ApiPaths.V1 + "/public/setup";

    private final FirstRunSetup setup;

    FirstRunSetupController(FirstRunSetup setup) {
        this.setup = setup;
    }

    @GetMapping(PATH)
    SetupStatus status() {
        return new SetupStatus(setup.required());
    }

    @PostMapping(PATH)
    ResponseEntity<SetupResult> complete(@Valid @RequestBody SetupRequest body, HttpServletRequest request) {
        String slug = setup.complete(body.setupCode(), body.displayName(), body.email(), body.password(),
                body.organizationName(), body.organizationSlug(), body.acceptTerms(), request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(new SetupResult(slug));
    }

    record SetupStatus(boolean required) {
    }

    record SetupRequest(@NotBlank @Size(max = 64) String setupCode, String displayName, String email, String password,
            String organizationName, String organizationSlug, Boolean acceptTerms) {
        @Override
        public String toString() {
            return "SetupRequest[email=" + email + ", password=****, setupCode=****]";
        }
    }

    record SetupResult(String organizationSlug) {
    }
}
