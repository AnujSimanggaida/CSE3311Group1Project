package coursesweep;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * "Sign in with Google" using the OAuth 2.0 authorization code flow with PKCE.
 *
 * The browser is sent to Google, Google sends it back to /api/google/callback
 * with a one-time code, and the server trades that code for an ID token by
 * calling Google directly. Because the ID token comes straight from Google
 * over HTTPS, its claims can be trusted without checking its signature
 * (OpenID Connect Core, section 3.1.3.7).
 */
final class GoogleAuth {

    /** A Google account that has proven it owns a verified email address. */
    record Identity(String subject, String email) {}

    /** Google sign-in failed in a way the user should be told about. */
    static final class Failure extends Exception {
        final String reason;

        Failure(String reason, String detail) {
            super(detail);
            this.reason = reason;
        }
    }

    private static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");
    private static final Base64.Encoder B64_URL = Base64.getUrlEncoder().withoutPadding();

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private GoogleAuth(String clientId, String clientSecret, String redirectUri) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    /** Returns null when GOOGLE_CLIENT_ID or GOOGLE_CLIENT_SECRET is not set, which turns Google sign-in off. */
    static GoogleAuth fromEnv(String baseUrl) {
        String clientId = System.getenv("GOOGLE_CLIENT_ID");
        String clientSecret = System.getenv("GOOGLE_CLIENT_SECRET");
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            return null;
        }
        return new GoogleAuth(clientId.trim(), clientSecret.trim(),
                baseUrl.replaceAll("/+$", "") + "/api/google/callback");
    }

    String redirectUri() {
        return redirectUri;
    }

    /** The Google page the browser is sent to. state and codeVerifier must be fresh random tokens. */
    String authorizationUrl(String state, String codeVerifier) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("response_type", "code");
        params.put("scope", "openid email");
        params.put("state", state);
        params.put("code_challenge", codeChallenge(codeVerifier));
        params.put("code_challenge_method", "S256");
        params.put("prompt", "select_account");
        return AUTHORIZE_URL + "?" + formEncode(params);
    }

    /** Trades the code from the callback for the user's Google identity. */
    Identity exchange(String code, String codeVerifier) throws IOException, InterruptedException, Failure {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", code);
        params.put("client_id", clientId);
        params.put("client_secret", clientSecret);
        params.put("redirect_uri", redirectUri);
        params.put("grant_type", "authorization_code");
        params.put("code_verifier", codeVerifier);

        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(params)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new Failure("failed", "Google token endpoint returned " + response.statusCode()
                    + ": " + response.body());
        }

        JsonObject claims = idTokenClaims(parseObject(response.body()));
        if (!ISSUERS.contains(stringClaim(claims, "iss"))) {
            throw new Failure("failed", "ID token has the wrong issuer.");
        }
        if (!clientId.equals(stringClaim(claims, "aud"))) {
            throw new Failure("failed", "ID token was issued for a different client.");
        }
        JsonElement exp = claims.get("exp");
        if (exp == null || !exp.isJsonPrimitive() || exp.getAsLong() <= System.currentTimeMillis() / 1000) {
            throw new Failure("failed", "ID token has expired.");
        }
        String subject = stringClaim(claims, "sub");
        String email = stringClaim(claims, "email");
        if (subject == null || email == null) {
            throw new Failure("failed", "ID token is missing the account id or email.");
        }
        JsonElement verified = claims.get("email_verified");
        if (verified == null || !verified.isJsonPrimitive() || !verified.getAsJsonPrimitive().isBoolean()
                || !verified.getAsBoolean()) {
            throw new Failure("unverified", "Google account email is not verified.");
        }
        return new Identity(subject, email);
    }

    private static JsonObject idTokenClaims(JsonObject tokenResponse) throws Failure {
        JsonElement idToken = tokenResponse.get("id_token");
        if (idToken == null || !idToken.isJsonPrimitive()) {
            throw new Failure("failed", "Google did not return an ID token.");
        }
        // A JWT is header.payload.signature; the claims are the base64url JSON payload.
        String[] parts = idToken.getAsString().split("\\.");
        if (parts.length != 3) {
            throw new Failure("failed", "ID token is malformed.");
        }
        try {
            return parseObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new Failure("failed", "ID token is malformed.");
        }
    }

    private static JsonObject parseObject(String json) throws Failure {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (JsonParseException e) {
            // fall through
        }
        throw new Failure("failed", "Google returned something that is not a JSON object.");
    }

    private static String stringClaim(JsonObject claims, String name) {
        JsonElement value = claims.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static String codeChallenge(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return B64_URL.encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String formEncode(Map<String, String> params) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> param : params.entrySet()) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(URLEncoder.encode(param.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }
}
