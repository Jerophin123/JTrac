package com.jtac.issue;

import java.time.Instant;
import java.util.UUID;

/** One entry of an issue's attributed history (changes to fields hidden from the viewer are omitted). */
public record IssueActivityEntry(UUID id, String kind, String field, Object oldValue, Object newValue,
        IssueSummary.Person actor, Instant at) {
}
