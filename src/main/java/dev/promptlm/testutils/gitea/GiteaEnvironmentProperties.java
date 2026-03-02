package dev.promptlm.testutils.gitea;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utility for managing process-wide environment bridge properties used by the
 * Gitea test harness.
 */
final class GiteaEnvironmentProperties {

    static final String REPO_REMOTE_URL = "REPO_REMOTE_URL";
    static final String REPO_REMOTE_USERNAME = "REPO_REMOTE_USERNAME";
    static final String REPO_REMOTE_TOKEN = "REPO_REMOTE_TOKEN";

    private GiteaEnvironmentProperties() {
        // Utility class
    }

    static Map<String, String> snapshot() {
        Map<String, String> previous = new LinkedHashMap<>();
        previous.put(REPO_REMOTE_URL, System.getProperty(REPO_REMOTE_URL));
        previous.put(REPO_REMOTE_USERNAME, System.getProperty(REPO_REMOTE_USERNAME));
        previous.put(REPO_REMOTE_TOKEN, System.getProperty(REPO_REMOTE_TOKEN));
        return previous;
    }

    static void applyFrom(GiteaContainer container) {
        System.setProperty(REPO_REMOTE_URL, container.getApiUrl());
        System.setProperty(REPO_REMOTE_USERNAME, container.getAdminUsername());
        System.setProperty(REPO_REMOTE_TOKEN, container.getAdminToken());
    }

    static void restore(Map<String, String> previous) {
        restoreSingle(REPO_REMOTE_URL, previous.get(REPO_REMOTE_URL));
        restoreSingle(REPO_REMOTE_USERNAME, previous.get(REPO_REMOTE_USERNAME));
        restoreSingle(REPO_REMOTE_TOKEN, previous.get(REPO_REMOTE_TOKEN));
    }

    private static void restoreSingle(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, previousValue);
    }
}
