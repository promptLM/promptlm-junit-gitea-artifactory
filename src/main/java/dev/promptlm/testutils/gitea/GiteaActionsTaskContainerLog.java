package dev.promptlm.testutils.gitea;

import java.time.Instant;
import java.util.List;

public record GiteaActionsTaskContainerLog(String containerId,
                                          List<String> containerNames,
                                          Instant createdAt,
                                          String logs) {
}
