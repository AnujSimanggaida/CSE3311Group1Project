package coursesweep;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Starts the CourseSweep server: the JSON API under /api/ and the static
 * front end from the public/ directory.
 *
 * Configuration (environment variables, all optional):
 *   PORT                        port to listen on (default 8080)
 *   COURSESWEEP_HOST            address to bind to (default localhost)
 *   COURSESWEEP_BASE_URL        public URL used in password reset links
 *   COURSESWEEP_DATA_DIR        where the database and key file live (default data)
 *   COURSESWEEP_SECRET_KEY      base64 32-byte key; replaces the key file
 *   COURSESWEEP_SECURE_COOKIES  "true" when served over HTTPS
 *   GOOGLE_CLIENT_ID            OAuth client for "Continue with Google"
 *   GOOGLE_CLIENT_SECRET        (Google sign-in is off unless both are set)
 *   COURSESWEEP_ADMIN_EMAILS    comma-separated emails of accounts that are always admins
 */

// Entry point of the whole app. 'final' means no other class can extend it; it is never instantiated, because it only holds main() and a small helper.
public final class Main {

    // Java starts here. It reads the settings, opens the database, builds the services and starts the web server. 'throws Exception' lets a start-up failure (such as a bad database path) stop the program with a clear error instead of running half set up.
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("PORT", "8080"));
        String host = env("COURSESWEEP_HOST", "localhost");
        String baseUrl = env("COURSESWEEP_BASE_URL", "http://localhost:" + port);
        Path dataDir = Path.of(env("COURSESWEEP_DATA_DIR", "data"));
        boolean secureCookies = Boolean.parseBoolean(env("COURSESWEEP_SECURE_COOKIES", "false"));

        Crypto crypto = Crypto.load(dataDir.resolve("secret.key"));
        Database db = new Database(dataDir.resolve("coursesweep.db"));
        db.init();
        CourseIndex courseIndex = CatalogSeed.run(db);

        AuthService auth = new AuthService(db, crypto, new Mailer.Console(), baseUrl);
        GoogleAuth google = GoogleAuth.fromEnv(baseUrl);
        Set<String> adminEmails = AdminService.parseEmails(env("COURSESWEEP_ADMIN_EMAILS", ""));
        AdminService admin = new AdminService(db, crypto, adminEmails);

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/", new ApiHandler(auth, new ScheduleService(db), admin, new DegreeService(db, courseIndex),
                new ProfileService(db, courseIndex), google, secureCookies));
        server.createContext("/", new StaticHandler(Path.of("public")));
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();

        // Start-up report printed to the terminal, so the person running the app can see that it worked and how it is configured. Line 1: the address to open in a browser.
        System.out.println("CourseSweep running at " + baseUrl);
        
        // Lines 2-4: whether "Continue with Google" is available. google is null unless both GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET are set; when on, it prints the redirect URI that must be registered in the Google OAuth client.
        System.out.println(google == null
                ? "Google sign-in is off (set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET to turn it on)"
                : "Google sign-in is on; redirect URI: " + google.redirectUri());
        
        // How many email addresses are configured to become admins (0 means nobody can use the Admin page).
        System.out.println(adminEmails.size() + " admin email(s) set in COURSESWEEP_ADMIN_EMAILS");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
