package com.jtac.identity.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's consent to the Terms of Service and Privacy Notice. */
@RestController
@RequestMapping(ApiPaths.V1 + "/me/consent")
class ConsentController {

    private final ConsentService consents;

    ConsentController(ConsentService consents) {
        this.consents = consents;
    }

    @GetMapping
    ResponseEntity<ConsentStatus> status(CurrentUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ConsentStatus(consents.currentVersion(),
                consents.hasCurrentConsent(user.id()), consents.history(user.id())));
    }

    @PostMapping
    ResponseEntity<Void> accept(CurrentUser user) {
        consents.accept(user);
        return ResponseEntity.noContent().build();
    }

    /** Withdraws consent and files an erasure request with the grievance officer. */
    @PostMapping("/withdraw")
    ResponseEntity<Void> withdraw(CurrentUser user, @Valid @RequestBody(required = false) Withdrawal body) {
        consents.withdraw(user, body == null ? null : body.reason());
        return ResponseEntity.accepted().build();
    }

    record ConsentStatus(String currentVersion, boolean accepted, List<ConsentService.ConsentRecord> history) {
    }

    record Withdrawal(@Size(max = 1000) String reason) {
    }
}
