package dev.promptlm.testutils.gitea;

public class GiteaApiException extends RuntimeException {

    private final int statusCode;
    private final String method;
    private final String url;
    private final String responseBody;

    public GiteaApiException(int statusCode, String method, String url, String responseBody) {
        super(buildMessage(statusCode, method, url, responseBody));
        this.statusCode = statusCode;
        this.method = method;
        this.url = url;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getMethod() {
        return method;
    }

    public String getUrl() {
        return url;
    }

    public String getResponseBody() {
        return responseBody;
    }

    private static String buildMessage(int statusCode, String method, String url, String responseBody) {
        String body = responseBody == null ? "" : responseBody.trim();
        if (body.length() > 2_000) {
            body = body.substring(0, 2_000) + "<<<truncated>>>";
        }
        if (body.isBlank()) {
            return "Gitea API request failed: HTTP " + statusCode + " for " + method + " " + url;
        }
        return "Gitea API request failed: HTTP " + statusCode + " for " + method + " " + url + " - " + body;
    }
}
