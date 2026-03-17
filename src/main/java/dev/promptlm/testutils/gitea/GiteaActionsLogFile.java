package dev.promptlm.testutils.gitea;

public record GiteaActionsLogFile(String path,
                                  long sizeBytes,
                                  String contents) {
}
