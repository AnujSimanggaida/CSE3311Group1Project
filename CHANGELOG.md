# Changelog

What changed in CourseSweep, newest first. Full instructions for every feature are in [README.md](README.md).

## Whole courses in search, and student ratings

### Added

- **Search is grouped by course.** **Select course (all N sections)** lets Find best schedules pick any of the course's sections. **Select only this section** keeps that one section. Each course is boxed in blue, with its sections in a second blue box.
- **Select, then save.** Search only selects courses. A bar pinned to the bottom of the search has a green **Save selected courses** button and **Exit without saving**.
- **Sections and ratings** on each course in your list shows all its sections. It can switch between the two ways a course is added.
- **Your rating.** Rate any professor's section from 1 to 5. Find best schedules uses your rating for that section. Every rating is saved and averaged into the section's overall rating, where each student counts as one survey response.
- **New API:** `GET /api/courses/{id}/sections` and `PUT /api/ratings`. Schedules and the schedule finder take `entries` (with `anySection` and `chosen`) instead of `courseIds`; `courseIds` still works.

### What you need to do after pulling

Restart the server. The new `section_ratings` table and the `any_section` and `section_chosen` columns are added automatically. Plans saved before this update keep working as before: each course counts as the whole course.

## Core curriculum, senior prerequisites and "courses my degree still needs"

### Added

- **Core curriculum courses.** The catalog gains 57 courses from UTA's General Core Requirements page: U.S. History, Political Science, Language/Philosophy/Culture and Creative Arts. Each comes with its official title, prerequisites and real instructors from the survey data. The three degree plans now list them as requirements, so "Classes left to graduate" counts them. Only free electives remain uncounted.
- **Core curriculum section on My degree.** U.S. History and Political Science are checkboxes; Language/Philosophy/Culture and Creative Arts each have a menu asking which course you took. These courses no longer crowd the checklist.
- **4000-level courses need Calculus II and Physics II.** Every 4000-level course now also requires MATH 2425 and PHYS 1444, for "Only show courses I can take now" and for automatic ticking on My degree.
- **"Only show courses my degree still needs"** in course search. It hides courses that cannot fill an open requirement of your major. Combine it with "can take now" to see what you could sign up for next.
- **Seed script.** `build_uta_seed.py` reads the general core page and more subject pages (see `SUBJECTS` and `CORE_PAGE`), and applies the 4000-level rule (`SENIOR_PREREQUISITES`).
- **New API option:** `needed=true` on `GET /api/courses`, with the count in `hiddenNotNeeded`.

### What you need to do after pulling

Restart the server. The new courses, sections and degree requirements are loaded automatically, and saved schedules keep their courses. Saved profiles gain MATH 2425 and PHYS 1444 for any 4000-level course the next time they are saved.

## Time blocks and best schedules

### Added

- **Time blocks** in the schedule builder: 0 to 6 weekly times you are busy, each with days and a start and end time picked from hour (1–12), minute (every 5) and AM/PM menus. They are saved with the schedule, and a course that overlaps one gets a warning.
- **Find best schedules.** Tries every section of each course in your list, skips sections that overlap a time block or clash, and ranks the rest by average rating (to the nearest 0.25), then by the least time between classes. Shows the best 10. **Use this schedule** puts one into your list.
- **"Create a new degree plan"** replaces "Create a new schedule" on the home page, My schedules and the builder, since CourseSweep now helps plan a whole degree.
- **Semester.** Choose the semester a plan is for (Fall 2026, Spring 2027 or Fall 2027) before saving. My schedules shows it.
- **"View existing degree plans"** replaces "View existing schedules" on the home page.
- **Explains empty results.** It names the courses whose every section overlaps a time block, or the pairs of courses that always clash.
- **New API:** `POST /api/schedule-options`. Saving a schedule accepts `blocks`, and opening one returns them.

### What you need to do after pulling

Restart the server. The new `time_blocks` and `semester` columns are added automatically. Schedules saved before have no time blocks and no semester; choose one the next time you save them.

## Courses I can take now

### Added

- **"Only show courses I can take now"** in course search. It hides courses whose prerequisites are not among the courses ticked on My degree, and the search says how many sections it hid. It is off by default and works alongside "Hide courses I have already taken".
- **Same-term prerequisites.** Where the catalog allows "concurrent enrollment", a prerequisite you could sign up for now counts, so a new student still sees CSE 1310. Prerequisites CourseSweep cannot check, such as ALEKS scores, count as met.
- **New data.** `uta-catalog.json` gains `concurrentOk` for each course. The seed script produces it.
- **New API option:** `canTake=true` on `GET /api/courses`, with the count in `hiddenNotReady`.

### What you need to do after pulling

Restart the server. Nothing in the database changes.

## Prerequisite auto-check and classes left to graduate

### Added

- **Professional courses first.** On My degree, all 3000- and 4000-level courses come first, then the 1000- and 2000-level ones.
- **Automatic prerequisites.** Ticking a course ticks every course it certainly requires, through the whole chain, and unticking a course unticks the courses that need it. Prerequisites with alternatives ("or", ALEKS scores, plan-specific rules) are never guessed. Saving applies the same rule on the server.
- **Classes left to graduate.** A panel on My degree shows how many classes are left in your degree plan, a progress bar, and each open requirement, and it updates as you tick courses. Core curriculum and elective hours the catalog does not list by course are shown separately.
- **New data.** `uta-catalog.json` gains `prerequisiteGroups` for each course and `notListedHours` for each degree. The seed script produces both.
- **New API:** `POST /api/progress`. `/api/profile` now includes `progress`, and `/api/colleges/{id}/courses` includes `requires`.

### What you need to do after pulling

Restart the server. The new columns are added automatically. Profiles saved before this update gain their prerequisites the next time they are saved.

## My degree and courses taken

### Added

- **My degree page** (`profile.html`), linked from the top bar and the home page. Choose your college (UTA by default) and your major, then tick the courses you have already taken. The list is grouped by your degree plan, with a search box.
- **"Hide courses I have already taken"** in course search. It is on by default, and the search says how many sections it hid.
- **`CourseIndex`.** At startup, every course code in `uta-catalog.json` goes into a `HashMap` from code to bit number.
- **Courses taken as a `BitSet`.** They are saved in the new `student_profiles` table along with the chosen major.
- **New API endpoints:** `GET`/`PUT /api/profile`, `GET /api/colleges/{id}/courses`, and the `hideTaken=true` option on `/api/courses`.

### Changed

- **`GET /api/courses` returns more.** It also returns `hiddenTaken`. The 50-result limit now applies after filtering.

### What you need to do after pulling

Restart the server. The new table is created automatically, and no new settings are needed.

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
