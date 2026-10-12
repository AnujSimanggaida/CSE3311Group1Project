package coursesweep;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The JSON API under /api/. */
final class ApiHandler implements HttpHandler {

    private static final String SESSION_COOKIE = "cs_session";
    private static final int MAX_BODY_BYTES = 10_000;

    private final AuthService auth;
    private final ScheduleService schedules;
    private final boolean secureCookies;

    ApiHandler(AuthService auth, ScheduleService schedules, boolean secureCookies) {
        this.auth = auth;
        this.schedules = schedules;
        this.secureCookies = secureCookies;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (ApiException e) {
            sendError(exchange, e.status, e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Something went wrong. Please try again.");
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws Exception {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if (path.equals("/api/me")) {
            requireMethod(method, "GET");
            String email = auth.currentUserEmail(sessionToken(exchange));
            if (email == null) {
                throw new ApiException(401, "Not signed in.");
            }
            sendUser(exchange, 200, email);
            return;
        }

        switch (path) {
            case "/api/signup" -> {
                requireMethod(method, "POST");
                JsonObject body = readJson(exchange);
                String email = string(body, "email");
                AuthService.Session session = auth.signUp(email, string(body, "password"));
                setSessionCookie(exchange, session);
                sendUser(exchange, 201, email.trim().toLowerCase());
            }
            case "/api/login" -> {
                requireMethod(method, "POST");
                JsonObject body = readJson(exchange);
                String email = string(body, "email");
                boolean remember = body.has("remember") && body.get("remember").isJsonPrimitive()
                        && body.get("remember").getAsBoolean();
                AuthService.Session session = auth.signIn(email, string(body, "password"), remember);
                setSessionCookie(exchange, session);
                sendUser(exchange, 200, email.trim().toLowerCase());
            }
            case "/api/logout" -> {
                requireMethod(method, "POST");
                readJson(exchange);
                auth.signOut(sessionToken(exchange));
                clearSessionCookie(exchange);
                send(exchange, 200, new JsonObject());
            }
            case "/api/forgot-password" -> {
                requireMethod(method, "POST");
                auth.requestPasswordReset(string(readJson(exchange), "email"));
                // Identical response whether or not the account exists.
                send(exchange, 200, new JsonObject());
            }
            case "/api/reset-password" -> {
                requireMethod(method, "POST");
                JsonObject body = readJson(exchange);
                auth.resetPassword(string(body, "token"), string(body, "password"));
                send(exchange, 200, new JsonObject());
            }
            case "/api/courses" -> {
                long userId = requireUser(exchange);
                if (method.equals("GET")) {
                    JsonObject result = new JsonObject();
                    result.add("courses", schedules.searchCourses(userId, queryParam(exchange, "q")));
                    send(exchange, 200, result);
                } else {
                    requireMethod(method, "POST");
                    JsonObject body = readJson(exchange);
                    send(exchange, 201, schedules.createCustomCourse(userId,
                            string(body, "name"),
                            optionalString(body, "professor"),
                            stringList(body, "days"),
                            string(body, "start"),
                            string(body, "end"),
                            optionalNumber(body, "rating")));
                }
            }
            case "/api/schedules" -> {
                requireMethod(method, "POST");
                long userId = requireUser(exchange);
                JsonObject body = readJson(exchange);
                List<Long> courseIds = new ArrayList<>();
                for (JsonElement id : array(body, "courseIds")) {
                    if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isNumber()) {
                        throw new ApiException(400, "Invalid field: courseIds.");
                    }
                    courseIds.add(id.getAsLong());
                }
                JsonObject result = new JsonObject();
                result.addProperty("id", schedules.createSchedule(userId, string(body, "name"), courseIds));
                send(exchange, 201, result);
            }
            default -> throw new ApiException(404, "Not found.");
        }
    }

    // ---------- Request helpers ----------

    private static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new ApiException(405, "Method not allowed.");
        }
    }

    private static JsonObject readJson(HttpExchange exchange) throws IOException {
        // Requiring a JSON content type also blocks cross-site form posts.
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            throw new ApiException(415, "Requests must be JSON.");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new ApiException(413, "Request is too large.");
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (JsonParseException e) {
            // fall through
        }
        throw new ApiException(400, "Request body must be a JSON object.");
    }

    private static String string(JsonObject body, String field) {
        JsonElement value = body.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new ApiException(400, "Missing field: " + field + ".");
        }
        return value.getAsString();
    }

    private static boolean isAbsent(JsonElement value) {
        return value == null || value.isJsonNull();
    }

    private static String optionalString(JsonObject body, String field) {
        return isAbsent(body.get(field)) ? null : string(body, field);
    }

    private static Double optionalNumber(JsonObject body, String field) {
        JsonElement value = body.get(field);
        if (isAbsent(value)) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new ApiException(400, "Invalid field: " + field + ".");
        }
        return value.getAsDouble();
    }

    private static JsonArray array(JsonObject body, String field) {
        JsonElement value = body.get(field);
        if (value == null || !value.isJsonArray()) {
            throw new ApiException(400, "Missing field: " + field + ".");
        }
        return value.getAsJsonArray();
    }

    private static List<String> stringList(JsonObject body, String field) {
        List<String> values = new ArrayList<>();
        for (JsonElement item : array(body, field)) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new ApiException(400, "Invalid field: " + field + ".");
            }
            values.add(item.getAsString());
        }
        return values;
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name)) {
                try {
                    return parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                } catch (IllegalArgumentException e) {
                    throw new ApiException(400, "Invalid query.");
                }
            }
        }
        return null;
    }

    private long requireUser(HttpExchange exchange) throws Exception {
        Long userId = auth.currentUserId(sessionToken(exchange));
        if (userId == null) {
            throw new ApiException(401, "Not signed in.");
        }
        return userId;
    }

    private static String sessionToken(HttpExchange exchange) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return null;
        }
        for (String header : headers) {
            for (String pair : header.split(";")) {
                String[] parts = pair.trim().split("=", 2);
                if (parts.length == 2 && parts[0].equals(SESSION_COOKIE) && !parts[1].isEmpty()) {
                    return parts[1];
                }
            }
        }
        return null;
    }

    // ---------- Response helpers ----------

    private void setSessionCookie(HttpExchange exchange, AuthService.Session session) {
        // HttpOnly keeps the token away from page scripts; SameSite=Strict stops cross-site requests using it.
        StringBuilder cookie = new StringBuilder(SESSION_COOKIE + "=" + session.token())
                .append("; Path=/; HttpOnly; SameSite=Strict");
        if (session.remembered()) {
            cookie.append("; Max-Age=").append(session.lifetimeSeconds());
        }
        if (secureCookies) {
            cookie.append("; Secure");
        }
        exchange.getResponseHeaders().add("Set-Cookie", cookie.toString());
    }

    private void clearSessionCookie(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Set-Cookie",
                SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0" + (secureCookies ? "; Secure" : ""));
    }

    private static void sendUser(HttpExchange exchange, int status, String email) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        send(exchange, status, body);
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        send(exchange, status, body);
    }

    private static void send(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
