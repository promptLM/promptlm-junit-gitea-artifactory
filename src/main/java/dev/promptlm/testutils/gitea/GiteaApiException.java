package dev.promptlm.testutils.gitea;

/**
 * Exception thrown when a Gitea REST API call returns a non-success response.
 */
public class GiteaApiException extends RuntimeException {

    /**
     * HTTP status code returned by Gitea.
     */
    private final int statusCode;

    /**
     * HTTP method used for the request.
     */
    private final String method;

    /**
     * Request URL.
     */
    private final String url;

    /**
     * Response body returned by Gitea.
     */
    private final String responseBody;

    /**
     * Create an API exception from an HTTP response.
     *
     * @param statusCode HTTP status code
     * @param method HTTP method
     * @param url requested URL
     * @param responseBody response body, if any
     */
    public GiteaApiException(int statusCode, String method, String url, String responseBody) {
        super(buildMessage(statusCode, method, url, responseBody));
        this.statusCode = statusCode;
        this.method = method;
        this.url = url;
        this.responseBody = responseBody;
    }

    /**
     * Get the HTTP status code.
     *
     * @return HTTP status code
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Get the HTTP method used for the failing request.
     *
     * @return HTTP method
     */
    public String getMethod() {
        return method;
    }

    /**
     * Get the failing request URL.
     *
     * @return request URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * Get the response body returned by Gitea.
     *
     * @return response body
     */
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
