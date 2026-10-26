package com.jtac.identity;

import java.util.UUID;

/** Public, non-sensitive view of a user account (safe to show to collaborators). */
public record UserSummary(UUID id, String email, String displayName, String handle, boolean active) {
}
