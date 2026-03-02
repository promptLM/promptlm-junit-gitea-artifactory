package dev.promptlm.testutils.gitea;

import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpResponse;

/**
 * Shared logging utilities for HTTP interactions performed by the Gitea test harness.
 */
final class HttpDebugLogger {

    private HttpDebugLogger() {
    }

    static void logHttpResponse(Logger logger, String method, String url, HttpResponse<String> response) {
        String httpVersion = response.version().toString();
        logger.info("HTTP {} {} -> {} (version {})", method, url, response.statusCode(), httpVersion);
        if (logger.isDebugEnabled()) {
            response.headers().map().forEach((k, v) -> logger.debug("Header {}: {}", k, v));
            logger.debug("Body: {}", response.body());
        }
    }

    static void logCurlCommand(Logger logger, String method, String url, String body, String adminToken) {
        URI uri = URI.create(url);
        StringBuilder base = new StringBuilder()
                .append(uri.getScheme())
                .append("://")
                .append(uri.getHost());
        if (uri.getPort() != -1) {
            base.append(":" ).append(uri.getPort());
        }
        base.append(uri.getRawPath());
        if (uri.getQuery() != null) {
            base.append('?').append(uri.getRawQuery());
        }

        StringBuilder sb = new StringBuilder()
                .append("curl -i -X ")
                .append(method)
                .append(" \\\n   \"")
                .append(base)
                .append("\" \\\n   -H \"Authorization: token ")
                .append(adminToken)
                .append("\"");
        if ("POST".equals(method) || "PUT".equals(method)) {
            sb.append(" \\\n  -H \"Content-Type: application/json\"");
        }
        if (body != null) {
            sb.append(" \\\n  --data-raw '")
                    .append(escapeForSingleQuotes(body))
                    .append("'");
        }

        logger.debug("Reproduce with:\n{}", sb);
    }

    private static String escapeForSingleQuotes(String input) {
        return input.replace("'", "'\\''");
    }
}
