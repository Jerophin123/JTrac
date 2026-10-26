package com.jtac.integration.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.organization.OrganizationAccess;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.ApiPaths;
import com.jtac.shared.web.PageResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class WebhookController {

    private static final String BASE = ApiPaths.V1 + "/orgs/{org}/webhooks";

    private final OrganizationAccess organizations;
    private final WebhookService webhooks;

    WebhookController(OrganizationAccess organizations, WebhookService webhooks) {
        this.organizations = organizations;
        this.webhooks = webhooks;
    }

    @GetMapping(BASE)
    List<WebhookService.Webhook> list(CurrentUser user, @PathVariable String org) {
        return webhooks.list(organizations.require(user, org));
    }

    @GetMapping(BASE + "/events")
    List<String> events(CurrentUser user, @PathVariable String org) {
        organizations.require(user, org).requireAdmin();
        return WebhookService.SUBSCRIBABLE.stream().sorted().toList();
    }

    @PostMapping(BASE)
    ResponseEntity<WebhookService.WebhookWithSecret> create(CurrentUser user, @PathVariable String org,
            @RequestBody WebhookService.WebhookInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(webhooks.create(organizations.require(user, org), body));
    }

    @GetMapping(BASE + "/{id}")
    WebhookService.Webhook get(CurrentUser user, @PathVariable String org, @PathVariable UUID id) {
        return webhooks.get(organizations.require(user, org), id);
    }

    record UpdateWebhook(long version, WebhookService.WebhookInput webhook) {
    }

    @PutMapping(BASE + "/{id}")
    WebhookService.Webhook update(CurrentUser user, @PathVariable String org, @PathVariable UUID id,
            @RequestBody UpdateWebhook body) {
        if (body.webhook() == null) {
            throw ApiErrors.invalid("webhook", "Required.");
        }
        return webhooks.update(organizations.require(user, org), id, body.version(), body.webhook());
    }

    @DeleteMapping(BASE + "/{id}")
    ResponseEntity<Void> delete(CurrentUser user, @PathVariable String org, @PathVariable UUID id) {
        webhooks.delete(organizations.require(user, org), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(BASE + "/{id}/rotate-secret")
    WebhookService.WebhookWithSecret rotate(CurrentUser user, @PathVariable String org, @PathVariable UUID id) {
        return webhooks.rotateSecret(organizations.require(user, org), id);
    }

    @PostMapping(BASE + "/{id}/ping")
    ResponseEntity<Map<String, UUID>> ping(CurrentUser user, @PathVariable String org, @PathVariable UUID id) {
        return ResponseEntity.accepted().body(Map.of("deliveryId", webhooks.ping(organizations.require(user, org), id)));
    }

    @GetMapping(BASE + "/{id}/deliveries")
    PageResponse<WebhookService.Delivery> deliveries(CurrentUser user, @PathVariable String org, @PathVariable UUID id,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return webhooks.deliveries(organizations.require(user, org), id, PageResponse.clampPage(page),
                PageResponse.clampSize(size, 25));
    }

    @GetMapping(BASE + "/{id}/deliveries/{deliveryId}")
    WebhookService.Delivery delivery(CurrentUser user, @PathVariable String org, @PathVariable UUID id,
            @PathVariable UUID deliveryId) {
        return webhooks.delivery(organizations.require(user, org), id, deliveryId);
    }

    @PostMapping(BASE + "/{id}/deliveries/{deliveryId}/redeliver")
    WebhookService.Delivery redeliver(CurrentUser user, @PathVariable String org, @PathVariable UUID id,
            @PathVariable UUID deliveryId) {
        return webhooks.redeliver(organizations.require(user, org), id, deliveryId);
    }
}
