# Changelog

What changed in CourseSweep, newest first. Full instructions for every feature are in [README.md](README.md).

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
