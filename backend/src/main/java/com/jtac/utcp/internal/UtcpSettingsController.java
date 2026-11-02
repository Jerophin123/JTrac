package com.jtac.utcp.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.ApiPaths;
import com.jtac.shared.web.PageResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for configuring and monitoring the user's native UTCP integration.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/me/utcp")
class UtcpSettingsController {

    private final UtcpService utcpService;
    private final UtcpProperties properties;
    private final UtcpUrls urls;

    UtcpSettingsController(UtcpService utcpService, UtcpProperties properties, UtcpUrls urls) {
        this.utcpService = utcpService;
        this.properties = properties;
        this.urls = urls;
    }

    record UtcpOptions(
            boolean available,
            String unavailableReason,
            boolean experimental,
            String manualUrl,
            String utcpVersion,
            List<String> supportedCategories,
            int totalTools
    ) {}

    record StatusResponse(String status, UtcpService.LifecycleState state) {}

    @GetMapping("/options")
    UtcpOptions options(CurrentUser user) {
        requireUser(user);
        String reason = !properties.enabled() ? "UTCP integration is disabled by server administrator." : null;
        List<String> categories = utcpService.listTools().stream()
                .map(UtcpToolDef::category)
                .distinct()
                .toList();

        return new UtcpOptions(
                properties.enabled(),
                reason,
                properties.experimental(),
                urls.manualUrl(),
                UtcpManual.SPEC_VERSION,
                categories,
                utcpService.listTools().size()
        );
    }

    @GetMapping("/settings")
    UtcpService.UtcpSettingsView settings(CurrentUser user, @RequestParam(required = false) UUID organizationId) {
        requireUser(user);
        return utcpService.getSettings(user, organizationId);
    }

    @PostMapping("/enable")
    StatusResponse enable(CurrentUser user, @RequestParam(required = false) UUID organizationId) {
        requireUser(user);
        utcpService.enable(user, organizationId);
        return new StatusResponse("enabled", UtcpService.LifecycleState.ENABLED);
    }

    @PostMapping("/disable")
    StatusResponse disable(CurrentUser user, @RequestParam(required = false) UUID organizationId) {
        requireUser(user);
        utcpService.disable(user, organizationId);
        return new StatusResponse("disabled", UtcpService.LifecycleState.DISABLED);
    }

    @GetMapping("/activity")
    PageResponse<UtcpService.ActivityView> activity(
            CurrentUser user,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        requireUser(user);
        return utcpService.activity(user, organizationId, PageResponse.clampPage(page), PageResponse.clampSize(size, 50));
    }

    private static void requireUser(CurrentUser user) {
        if (user == null) {
            throw ApiErrors.forbidden("Manage UTCP integrations from an authenticated session.");
        }
    }
}

