# Changelog

What changed in CourseSweep, newest first. Full instructions for every feature are in [README.md](README.md).

## Real UTA catalog data, colleges and degrees

### Added

- **Colleges, degrees and required courses** in new database tables:
  - UTA is the first college and the default.
  - It has three degrees: Computer Science, Software Engineering and Computer Engineering.
  - Each degree has its required courses from the UTA 2026–2027 catalog, including "or" alternatives and choose-N-hours elective groups.
- **81 UTA courses** with official titles, credit hours, Texas common course numbers and prerequisite wording.
- **UTA Student Feedback Survey results** for Fall 2025, Spring 2026 and Summer 2026.
- **`FeedbackImport`**, which turns a new survey PDF into data the app loads. See "Updating the catalog data" in the README.
- **`tools/catalog/build_uta_seed.py`**, which rebuilds the catalog data for a new catalog year.
- **New API endpoints:** `/api/colleges` and `/api/degrees/{id}`.

### Changed

- **The made-up catalog is gone.** The search now offers about 250 sections:
  - Each section is a real instructor who taught the course, rated with UTA's survey averages, with the response count shown.
  - Prerequisites appear under each course.
  - Meeting times are still sample data, tagged **Sample time**.
- **Saved schedules keep their courses.** Each of the 8 old fake courses becomes a real section of the same course, so its professor, time and rating change.
- **The catalog updates itself on startup.** You no longer need to delete the database after changing catalog data.
- **New dependency:** `pom.xml` adds Apache PDFBox, used only by `FeedbackImport`.

### What you need to do after pulling

Rebuild, because `pom.xml` changed: `docker compose up --build`. Without Docker, `./mvnw compile exec:java` downloads the new library by itself.

## Saved schedules

### Added

- **My schedules page** (`schedules.html`). **View existing schedules** on the home page now opens it. It lists your saved schedules, with the most recently saved first, and offers **Open**, **Rename** and **Delete** for each. Delete asks for confirmation first.
- **Editing saved schedules.** **Open** loads a schedule into the builder (`schedule.html?id=<number>`), where you can change its name and courses and save again.
- **Removed-course warnings.** If a course in a saved schedule is later removed, the schedule shows which course is missing instead of dropping it silently. Saving the schedule removes the missing course for good.
- **New API endpoints:** `GET /api/schedules`, and `GET`, `PUT`, `PATCH` and `DELETE` on `/api/schedules/{id}`.

### Changed

- **Saving no longer locks the builder.** The first save creates the schedule. Later saves update the same schedule instead of making a copy.
- **`POST /api/schedules` returns the whole saved schedule**, not just its `id`.
- **Privacy checks.** Schedules belong to one account, whether you sign in with a password or with Google. The server makes sure you can only read, change or delete your own schedules.
- **Database changes:**
  - Schedules get an `updated_at` time.
  - `schedule_courses` now stores each course's position and name.
  - Existing databases are converted automatically the next time the server starts, keeping all schedules.

### What you need to do after pulling

Restart the server: `docker compose up --build`, or `./mvnw compile exec:java` without Docker. No new settings are needed.

## Sign in with Google and admin accounts

### Added

- **Sign in with Google.** The sign in and create account pages have a **Continue with Google** button. It appears only when the server has a Google OAuth client ID and secret.
  - The first Google sign-in creates a CourseSweep account. Signing in again uses the same account.
  - If a password account with the same email already exists, Google sign-in links to it and keeps its schedules and custom courses. Linking replaces the old password and signs the account out on every device. Use **Forgot password** to set a password again.
- **Admin accounts.** Admins see an **Admin** link in the top bar. It opens a **Manage users** page where they can:
  - see every account, with its join date, schedules, custom courses and active sessions,
  - make another account an admin, or remove its admin access,
  - sign a user out on every device,
  - delete an account, along with its schedules and custom courses.
- **`COURSESWEEP_ADMIN_EMAILS` setting** lists the accounts that are always admins.
- **`.env.example`**, a template for the `.env` file that holds the Google keys and the admin list.
- **New API endpoints:**
  - `/api/auth-options`
  - `/api/google/start` and `/api/google/callback`
  - `/api/admin/users`, `/api/admin/users/set-admin`, `/api/admin/users/sign-out` and `/api/admin/users/delete`

### Changed

- `/api/me` now also returns `admin` (`true` or `false`).
- `.env` is ignored by git and kept out of the Docker image.
- The database gets two new columns in the `users` table: `google_index` and `is_admin`. They are added automatically the next time the server starts, and existing accounts, schedules and courses are kept.

### What you need to do after pulling

1. Copy `.env.example` to a new file named `.env` in the project folder.
2. Ask Anuj for the Google client ID and secret, and paste them into `.env`. Never commit `.env` or post the secret in a shared channel.
3. Add your Google account email to `COURSESWEEP_ADMIN_EMAILS` in `.env` so you are an admin on your own machine.
4. Send Anuj the Google account you will sign in with, so it can be added as a test user. Until then, Google blocks your sign-in.
5. Restart the server: `docker compose up --build`. Without Docker, load `.env` first, then run `./mvnw compile exec:java`.

Everything else works without a `.env` file. Without Google keys, the Google button stays hidden. Without an admin list, nobody is an admin.

## Docker development setup

### Added

- **`Dockerfile` and `docker-compose.yml`.** You can run the app with only Docker installed, without installing Java or Maven: `docker compose up --build`.
- **The project folder is shared with the container.** Changes to the front end show up when you refresh the browser. Java changes need a restart.
- **Build output and the database live in Docker volumes,** separate from the `target/` and `data/` folders used without Docker. `docker compose down -v` resets the database.
