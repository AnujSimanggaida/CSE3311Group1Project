package coursesweep;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Serves the front end from the public/ directory, and nothing outside it. */
final class StaticHandler implements HttpHandler {

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "png", "image/png",
            "jpg", "image/jpeg",
            "svg", "image/svg+xml",
            "ico", "image/x-icon");

    private final Path root;

    StaticHandler(Path root) throws IOException {
        this.root = root.toRealPath();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                sendText(exchange, 405, "Method not allowed");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/")) {
                path += "index.html";
            }
            Path file = root.resolve(path.substring(1)).normalize();
            // normalize() collapses any "../", so this check keeps requests inside public/.
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                sendText(exchange, 404, "Not found");
                return;
            }

            String name = file.getFileName().toString();
            String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
            byte[] bytes = Files.readAllBytes(file);

            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", CONTENT_TYPES.getOrDefault(extension, "application/octet-stream"));
            headers.set("Cache-Control", "no-cache");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Frame-Options", "DENY");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("Content-Security-Policy", "default-src 'self'");

            if (method.equals("HEAD")) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        } finally {
            exchange.close();
        }
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
