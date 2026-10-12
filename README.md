# CourseSweep

CourseSweep helps college students build the best possible class schedule from the classes and time blocks available to them.

So far it covers accounts (creating an account, signing in, resetting a forgotten password) and a schedule builder where students add courses and save the result. Automatically picking the best schedule is not built yet.

## What is in this version

- **Sign in page** with email and password validation, a show/hide password toggle, and a "Keep me signed in" option.
- **Create account page.**
- **Forgot password and reset password pages.** A reset link works once and expires after 30 minutes.
- **Home page** shown after signing in. It asks whether to create a new schedule or view an existing one. Viewing existing schedules is a placeholder for now.
- **Schedule builder.** Name a schedule, add courses, and save it. "Add course" offers two choices:
  - **Search for an existing course** by code, name or professor.
  - **Create a custom course** with a name, professor, time slot (days, start and end time) and a rating from 1 to 5. Professor and rating are optional. Custom courses are visible only to the student who made them.
- **Time conflict warnings** when two courses in a schedule overlap.
- **Sample course catalog** of eight UTA Computer Science courses. See the note below.
- **Java backend** that serves the pages and a JSON API, storing accounts in a SQLite database.

### The course catalog is sample data

The eight catalog courses use real UTA course codes and titles (CSE 1310, 1320, 1325, 2312, 2315, 3311, 3318 and 3320). **The professor names, time slots and ratings are made up** and need to be replaced with real data. They live in `SAMPLE_CATALOG` in `Database.java`.

The catalog is only inserted when the database has no catalog courses. After editing `SAMPLE_CATALOG`, delete `data/coursesweep.db` to load the new list. This also deletes local accounts and schedules.

### Planned: sharing custom courses through search

Today a custom course appears in search only for the student who created it. Nobody else can see it.

In a future update, a custom course will be added to the shared search results for everyone, but only once **at least 3 different users** have each created a custom course with the same:

- professor name,
- course name, and
- time slot.

Until that threshold is reached, the course and its professor stay private to the students who entered them. This keeps typos, jokes and one-off entries out of the shared catalog. **This is not implemented yet.**

## Requirements

- Java 17 or newer (`java -version` to check).
- An internet connection the first time you run it, to download Maven and the libraries.

You do not need to install Maven. The `mvnw` script included in the project downloads it for you.

## How to run

From the project folder:

```
./mvnw compile exec:java
```

On Windows, use `mvnw.cmd compile exec:java` instead.

When you see `CourseSweep running at http://localhost:8080`, open that address in a browser. Press `Ctrl+C` in the terminal to stop the server.

The pages must be opened through the server. Opening the HTML files directly will not work, because they need the API.

### Trying the password reset

No email service is connected yet. When you request a reset, the link is printed in the terminal where the server is running. Copy it into your browser to continue.

## Settings

All settings are optional environment variables.

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | Port the server listens on |
| `COURSESWEEP_HOST` | `localhost` | Address the server binds to |
| `COURSESWEEP_BASE_URL` | `http://localhost:<PORT>` | Address used in password reset links |
| `COURSESWEEP_DATA_DIR` | `data` | Folder for the database and the secret key |
| `COURSESWEEP_SECRET_KEY` | none | Base64 32-byte key, used instead of the key file |
| `COURSESWEEP_SECURE_COOKIES` | `false` | Set to `true` when serving over HTTPS |

Example: `PORT=9000 ./mvnw compile exec:java`

## Project layout

```
public/                      Front end (HTML, CSS, JavaScript, logo)
  index.html                 Sign in
  signup.html                Create account
  forgot.html                Request a password reset
  reset.html                 Choose a new password
  home.html                  Page shown after signing in
  schedule.html              Schedule builder and the Add course dialog
  js/auth.js                 Calls the account API
  js/form.js                 Validation shared by the forms
  js/topbar.js               Sign-in check and Sign out button for signed-in pages
  js/schedule.js             Schedule builder logic
src/main/java/coursesweep/   Back end
  Main.java                  Starts the server
  ApiHandler.java            The /api/ endpoints
  AuthService.java           Accounts, sessions and password resets
  ScheduleService.java       Course search, custom courses and saved schedules
  Crypto.java                Hashing and encryption
  Database.java              SQLite connection, tables and the sample catalog
  Mailer.java                Sends reset emails (prints to the console for now)
  StaticHandler.java         Serves the files in public/
pom.xml                      Build file and library list
data/                        Created on first run; not committed
```

## API

All requests and responses are JSON.

| Method | Path | Body | Result |
|---|---|---|---|
| POST | `/api/signup` | `email`, `password` | Creates the account and signs in |
| POST | `/api/login` | `email`, `password`, `remember` | Signs in |
| POST | `/api/logout` | `{}` | Signs out |
| GET | `/api/me` | | The signed-in user's email, or 401 |
| POST | `/api/forgot-password` | `email` | Sends a reset link if the account exists |
| POST | `/api/reset-password` | `token`, `password` | Sets a new password |
| GET | `/api/courses?q=` | | Catalog courses and your custom courses matching the search |
| POST | `/api/courses` | `name`, `professor`, `days`, `start`, `end`, `rating` | Creates a custom course |
| POST | `/api/schedules` | `name`, `courseIds` | Saves a schedule |

The course and schedule endpoints require being signed in. `days` is a list of day codes (`Mo`, `Tu`, `We`, `Th`, `Fr`, `Sa`, `Su`), and `start` and `end` are 24-hour times such as `09:30`.

Errors come back as `{"error": "message"}` with a matching HTTP status.

## How account data is protected

Nothing sensitive is stored in readable form.

| Data | How it is stored |
|---|---|
| Passwords | Salted PBKDF2-HMAC-SHA256 hash, 600,000 iterations, with a new random salt for each user |
| Email addresses | Encrypted with AES-256-GCM, plus a keyed hash used to look a user up |
| Session and reset tokens | SHA-256 hash only |

Passwords are hashed, not encrypted, so they cannot be recovered even by someone who has both the database and the key.

Other protections:

- The session token is kept in an `HttpOnly`, `SameSite=Strict` cookie, so page scripts cannot read it.
- Sign in gives the same error for a wrong password and an unknown email, and forgot password gives the same response either way, so neither reveals who has an account.
- Resetting a password signs the account out everywhere.

### The secret key

The first run creates `data/secret.key`. This key protects the stored email addresses.

- **Do not commit it.** The `data/` folder is listed in `.gitignore` for this reason.
- **Do not lose it.** Without it, existing accounts can no longer sign in.
- Each teammate's machine generates its own key and its own database, so accounts are not shared between machines.

## Known limitations

- Saved schedules cannot be viewed, edited or deleted yet.
- The catalog's professors, time slots and ratings are sample data.
- Custom courses are never shared with other students yet (see the planned feature above).
- Reset emails are printed to the console instead of being sent.
- There is no limit on repeated sign-in attempts.
- On localhost the connection is plain HTTP. Any real deployment needs HTTPS and `COURSESWEEP_SECURE_COOKIES=true`.
- There are no automated tests yet.
