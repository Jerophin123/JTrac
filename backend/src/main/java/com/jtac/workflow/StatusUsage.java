package com.jtac.workflow;

import java.util.Map;
import java.util.UUID;

/** SPI implemented by the issue module: how many issues are in each status of a project. */
public interface StatusUsage {

    Map<UUID, Long> issueCountsByStatus(UUID projectId);
}
