package dev.promptlm.testutils.gitea;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GiteaRawFileClientTest {

    private HttpServer server;
    private HttpClient httpClient;
    private GiteaRawFileClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        httpClient = HttpClient.newHttpClient();
        client = new GiteaRawFileClient(
                httpClient,
                org.slf4j.LoggerFactory.getLogger(GiteaRawFileClientTest.class),
                this::baseUrl,
                () -> "token test"
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("fetchRawFile returns contents when server responds 200")
    void fetchRawFileReturnsContents() {
        registerHandler("/owner/repo/raw/branch/main/path/to/file", exchange -> {
            assertAuthorization(exchange);
            respond(exchange, 200, "hello world");
        });

        Optional<String> result = client.fetchRawFile("owner", "repo", "main", "path/to/file");
        assertThat(result).contains("hello world");
    }

    @Test
    @DisplayName("fetchRawFileBytes returns empty when server responds 404")
    void fetchRawFileBytesHandlesNotFound() {
        registerHandler("/owner/repo/raw/branch/main/path/to/missing", exchange -> {
            assertAuthorization(exchange);
            respond(exchange, 404, "missing");
        });

        Optional<byte[]> result = client.fetchRawFileBytes("owner", "repo", "main", "path/to/missing");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("fetchRawFile throws when server responds with error")
    void fetchRawFileThrowsOnError() {
        registerHandler("/owner/repo/raw/branch/main/bad", exchange -> {
            assertAuthorization(exchange);
            respond(exchange, 500, "boom");
        });

        assertThatThrownBy(() -> client.fetchRawFile("owner", "repo", "main", "bad"))
                .isInstanceOf(GiteaHarnessException.class)
                .hasMessageContaining("status=500")
                .hasMessageContaining("boom");
    }

    @Test
    @DisplayName("waitForRawFile polls until file exists")
    void waitForRawFilePolls() {
        AtomicInteger counter = new AtomicInteger();
        registerHandler("/owner/repo/raw/branch/main/path/to/file", exchange -> {
            assertAuthorization(exchange);
            if (counter.getAndIncrement() == 0) {
                respond(exchange, 404, "not yet");
            } else {
                respond(exchange, 200, "ready");
            }
        });

        String result = client.waitForRawFile("owner", "repo", "main", "path/to/file",
                Duration.ofSeconds(2), Duration.ofMillis(50));
        assertThat(result).isEqualTo("ready");
    }

    private void registerHandler(String path, HttpHandler handler) {
        server.createContext(path, handler);
    }

    private void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void assertAuthorization(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        assertThat(auth).isEqualTo("token token test");
    }

    private String baseUrl() {
        int port = server.getAddress().getPort();
        return "http://localhost:" + port;
    }
}
