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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** The JSON API under /api/. */
final class ApiHandler implements HttpHandler {

    private static final String SESSION_COOKIE = "cs_session";
    private static final String SCHEDULE_PREFIX = "/api/schedules/";
    private static final String DEGREE_PREFIX = "/api/degrees/";
    // Holds the state and PKCE verifier while the browser is away at Google.
    private static final String GOOGLE_COOKIE = "cs_google";
    private static final int GOOGLE_COOKIE_SECONDS = 10 * 60;
    private static final int MAX_BODY_BYTES = 10_000;

    private final AuthService auth;
    private final ScheduleService schedules;
    private final AdminService admin;
    private final DegreeService degrees;
    private final GoogleAuth google;
    private final boolean secureCookies;

    /** google is null when Google sign-in is not configured. */
    ApiHandler(AuthService auth, ScheduleService schedules, AdminService admin, DegreeService degrees,
               GoogleAuth google, boolean secureCookies) {
        this.auth = auth;
        this.schedules = schedules;
        this.admin = admin;
        this.degrees = degrees;
        this.google = google;
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
            String token = sessionToken(exchange);
            String email = auth.currentUserEmail(token);
            Long userId = auth.currentUserId(token);
            if (email == null || userId == null) {
                throw new ApiException(401, "Not signed in.");
            }
            JsonObject result = new JsonObject();
            result.addProperty("email", email);
            result.addProperty("admin", admin.isAdmin(userId));
            send(exchange, 200, result);
            return;
        }

        if (path.startsWith(DEGREE_PREFIX)) {
            requireMethod(method, "GET");
            requireUser(exchange);
            send(exchange, 200, degrees.getDegree(pathId(path.substring(DEGREE_PREFIX.length()))));
            return;
        }
        if (path.startsWith(SCHEDULE_PREFIX)) {
            routeSchedule(exchange, method, path.substring(SCHEDULE_PREFIX.length()));
            return;
        }

        switch (path) {
            case "/api/auth-options" -> {
                requireMethod(method, "GET");
                JsonObject result = new JsonObject();
                result.addProperty("google", google != null);
                send(exchange, 200, result);
            }
            case "/api/google/start" -> {
                requireMethod(method, "GET");
                startGoogleSignIn(exchange);
            }
            case "/api/google/callback" -> {
                requireMethod(method, "GET");
                finishGoogleSignIn(exchange);
            }
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
            case "/api/colleges" -> {
                requireMethod(method, "GET");
                requireUser(exchange);
                JsonObject result = new JsonObject();
                result.add("colleges", degrees.listColleges());
                send(exchange, 200, result);
            }
            case "/api/schedules" -> {
                long userId = requireUser(exchange);
                if (method.equals("GET")) {
                    JsonObject result = new JsonObject();
                    result.add("schedules", schedules.listSchedules(userId));
                    send(exchange, 200, result);
                } else {
                    requireMethod(method, "POST");
                    JsonObject body = readJson(exchange);
                    long id = schedules.createSchedule(userId, string(body, "name"), idList(body, "courseIds"));
                    send(exchange, 201, schedules.getSchedule(userId, id));
                }
            }
            case "/api/admin/users" -> {
                requireMethod(method, "GET");
                JsonObject result = new JsonObject();
                result.add("users", admin.listUsers(requireAdmin(exchange)));
                send(exchange, 200, result);
            }
            case "/api/admin/users/set-admin" -> {
                requireMethod(method, "POST");
                long adminId = requireAdmin(exchange);
                JsonObject body = readJson(exchange);
                admin.setAdmin(adminId, id(body, "id"), bool(body, "admin"));
                send(exchange, 200, new JsonObject());
            }
            case "/api/admin/users/sign-out" -> {
                requireMethod(method, "POST");
                long adminId = requireAdmin(exchange);
                admin.signOutEverywhere(adminId, id(readJson(exchange), "id"));
                send(exchange, 200, new JsonObject());
            }
            case "/api/admin/users/delete" -> {
                requireMethod(method, "POST");
                long adminId = requireAdmin(exchange);
                admin.deleteUser(adminId, id(readJson(exchange), "id"));
                send(exchange, 200, new JsonObject());
            }
            default -> throw new ApiException(404, "Not found.");
        }
    }

    /** /api/schedules/{id}: one of the signed-in user's saved schedules. */
    private void routeSchedule(HttpExchange exchange, String method, String rawId) throws Exception {
        long userId = requireUser(exchange);
        long scheduleId = pathId(rawId);
        switch (method) {
            case "GET" -> send(exchange, 200, schedules.getSchedule(userId, scheduleId));
            case "PUT" -> {
                JsonObject body = readJson(exchange);
                schedules.updateSchedule(userId, scheduleId, string(body, "name"), idList(body, "courseIds"));
                send(exchange, 200, schedules.getSchedule(userId, scheduleId));
            }
            case "PATCH" -> {
                schedules.renameSchedule(userId, scheduleId, string(readJson(exchange), "name"));
                send(exchange, 200, schedules.getSchedule(userId, scheduleId));
            }
            case "DELETE" -> {
                schedules.deleteSchedule(userId, scheduleId);
                send(exchange, 200, new JsonObject());
            }
            default -> throw new ApiException(405, "Method not allowed.");
        }
    }

    // ---------- Google sign-in ----------
    //
    // These two endpoints are opened by the browser itself, not by fetch(), so
    // they answer with redirects instead of JSON. Failures go back to the sign
    // in page as ?google=<reason>, which public/js/google.js turns into a message.

    private void startGoogleSignIn(HttpExchange exchange) throws IOException {
        if (google == null) {
            redirect(exchange, "/index.html?google=unavailable");
            return;
        }
        String state = Crypto.newToken();
        String codeVerifier = Crypto.newToken();
        String remember = "true".equals(queryParam(exchange, "remember")) ? "1" : "0";
        // SameSite=Lax, not Strict: the callback is a navigation coming from Google's site,
        // and a Strict cookie would not be sent with it. Path limits it to /api/google.
        exchange.getResponseHeaders().add("Set-Cookie", GOOGLE_COOKIE + "=" + state + "." + codeVerifier + "." + remember
                + "; Path=/api/google; HttpOnly; SameSite=Lax; Max-Age=" + GOOGLE_COOKIE_SECONDS
                + (secureCookies ? "; Secure" : ""));
        redirect(exchange, google.authorizationUrl(state, codeVerifier));
    }

    private void finishGoogleSignIn(HttpExchange exchange) throws IOException {
        // The cookie is single use, whatever happens next.
        exchange.getResponseHeaders().add("Set-Cookie", GOOGLE_COOKIE
                + "=; Path=/api/google; HttpOnly; SameSite=Lax; Max-Age=0" + (secureCookies ? "; Secure" : ""));
        if (google == null) {
            redirect(exchange, "/index.html?google=unavailable");
            return;
        }
        String reason;
        try {
            if (queryParam(exchange, "error") != null) {
                // Most often "access_denied": the user pressed Cancel on Google's page.
                redirect(exchange, "/index.html?google=cancelled");
                return;
            }
            // Cookie value is state.codeVerifier.remember; tokens are base64url, so never contain ".".
            String savedCookie = cookie(exchange, GOOGLE_COOKIE);
            String[] saved = savedCookie == null ? new String[0] : savedCookie.split("\\.");
            String state = queryParam(exchange, "state");
            String code = queryParam(exchange, "code");
            // Checking state proves this callback belongs to a sign-in started in this browser.
            if (saved.length != 3 || state == null || code == null
                    || !MessageDigest.isEqual(saved[0].getBytes(StandardCharsets.UTF_8),
                    state.getBytes(StandardCharsets.UTF_8))) {
                throw new GoogleAuth.Failure("expired", "Missing or mismatched state.");
            }
            GoogleAuth.Identity identity = google.exchange(code, saved[1]);
            setSessionCookie(exchange, auth.signInWithGoogle(identity, saved[2].equals("1")));
            redirect(exchange, "/home.html");
            return;
        } catch (GoogleAuth.Failure e) {
            System.err.println("Google sign-in failed: " + e.getMessage());
            reason = e.reason;
        } catch (Exception e) {
            e.printStackTrace();
            reason = "failed";
        }
        redirect(exchange, "/index.html?google=" + reason);
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

    private static long id(JsonObject body, String field) {
        JsonElement value = body.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new ApiException(400, "Missing field: " + field + ".");
        }
        return value.getAsLong();
    }

    /** The numeric id at the end of a path such as /api/schedules/12; anything else is 404. */
    private static long pathId(String rawId) {
        if (!rawId.matches("\\d{1,18}")) {
            throw new ApiException(404, "Not found.");
        }
        return Long.parseLong(rawId);
    }

    private static List<Long> idList(JsonObject body, String field) {
        List<Long> ids = new ArrayList<>();
        for (JsonElement id : array(body, field)) {
            if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isNumber()) {
                throw new ApiException(400, "Invalid field: " + field + ".");
            }
            ids.add(id.getAsLong());
        }
        return ids;
    }

    private static boolean bool(JsonObject body, String field) {
        JsonElement value = body.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new ApiException(400, "Missing field: " + field + ".");
        }
        return value.getAsBoolean();
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

    private long requireAdmin(HttpExchange exchange) throws Exception {
        long userId = requireUser(exchange);
        if (!admin.isAdmin(userId)) {
            throw new ApiException(403, "Only admins can do that.");
        }
        return userId;
    }

    private static String sessionToken(HttpExchange exchange) {
        return cookie(exchange, SESSION_COOKIE);
    }

    private static String cookie(HttpExchange exchange, String name) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return null;
        }
        for (String header : headers) {
            for (String pair : header.split(";")) {
                String[] parts = pair.trim().split("=", 2);
                if (parts.length == 2 && parts[0].equals(name) && !parts[1].isEmpty()) {
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

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(302, -1);
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
