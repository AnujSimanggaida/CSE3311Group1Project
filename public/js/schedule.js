// Schedule builder: a named list of courses, added either from the catalog
// or as custom courses, then saved to the server. With ?id=<schedule id> it
// opens that saved schedule for viewing and editing instead.

const DAY_LABELS = { Mo: 'Mon', Tu: 'Tue', We: 'Wed', Th: 'Thu', Fr: 'Fri', Sa: 'Sat', Su: 'Sun' };

const builderHeading = document.getElementById('builder-heading');
const loadError = document.getElementById('load-error');
const builder = document.getElementById('builder');
const missingPanel = document.getElementById('missing-panel');
const missingList = document.getElementById('missing-list');
const nameInput = document.getElementById('schedule-name');
const addCourseBtn = document.getElementById('add-course');
const emptyNote = document.getElementById('empty-note');
const courseList = document.getElementById('course-list');
const saveBtn = document.getElementById('save-schedule');
const saveError = document.getElementById('save-error');
const savedPanel = document.getElementById('saved');
const savedMessage = document.getElementById('saved-message');

const dialog = document.getElementById('add-dialog');
const dialogTitle = document.getElementById('dialog-title');
const dialogBack = document.getElementById('dialog-back');
const views = {
  choose: document.getElementById('view-choose'),
  search: document.getElementById('view-search'),
  custom: document.getElementById('view-custom'),
};
const viewTitles = {
  choose: 'Add a course',
  search: 'Search for a course',
  custom: 'Create a custom course',
};

const searchInput = document.getElementById('search-input');
const searchStatus = document.getElementById('search-status');
const searchResults = document.getElementById('search-results');

const customForm = views.custom;
const customError = document.getElementById('custom-error');
const customSubmit = document.getElementById('custom-submit');
const cName = document.getElementById('c-name');
const cProfessor = document.getElementById('c-professor');
const cStart = document.getElementById('c-start');
const cEnd = document.getElementById('c-end');
const cRating = document.getElementById('c-rating');
const cDaysError = document.getElementById('c-days-error');
const dayBoxes = [...customForm.querySelectorAll('input[name="day"]')];

// Courses in the schedule being built, in the order they were added.
let courses = [];
// Id of the saved schedule being edited, or null for one not saved yet.
let scheduleId = null;

// ---------- Formatting ----------

function formatTime(time) {
  const [hours, minutes] = time.split(':').map(Number);
  const suffix = hours < 12 ? 'AM' : 'PM';
  return `${hours % 12 || 12}:${String(minutes).padStart(2, '0')} ${suffix}`;
}

function formatSlot(course) {
  const days = course.days.map((day) => DAY_LABELS[day]).join(', ');
  return `${days} · ${formatTime(course.start)} – ${formatTime(course.end)}`;
}

function courseTitle(course) {
  return course.code ? `${course.code} · ${course.name}` : course.name;
}

function shortName(course) {
  return course.code || course.name;
}

// Two courses conflict when they share a day and their times overlap.
// "HH:MM" strings compare correctly as text.
function conflicts(a, b) {
  return a.days.some((day) => b.days.includes(day)) && a.start < b.end && b.start < a.end;
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

// A custom course's rating is the student's own. A catalog rating is the average of the five
// UTA Student Feedback Survey questions for that instructor and course.
function ratingTag(course) {
  if (course.rating == null) {
    return el('span', 'tag', course.custom ? 'No rating' : 'No UTA feedback data');
  }
  if (course.custom) return el('span', 'tag', `★ ${course.rating.toFixed(1)} · your rating`);
  const responses = `${course.ratingResponses} response${course.ratingResponses === 1 ? '' : 's'}`;
  const tag = el('span', 'tag', `★ ${course.rating.toFixed(2)} UTA feedback · ${responses}`);
  tag.title = `Average of the five UTA Student Feedback Survey questions for this instructor and course `
    + `(${course.ratingTerms}). Scores are 1 to 5.`;
  return tag;
}

// Builds one course row; `action` is the button shown on the right, if any.
function courseItem(course, action) {
  const item = el('li', 'course');
  const info = el('div', 'course-info');
  info.append(el('p', 'course-title', courseTitle(course)));

  const professor = course.professor || (course.custom ? 'No professor listed' : 'Instructor not yet available');
  info.append(el('p', 'course-meta', `${professor} · ${formatSlot(course)}`));
  if (course.prerequisites) info.append(el('p', 'course-prereq', course.prerequisites));

  const tags = el('p', 'course-tags');
  tags.append(ratingTag(course));
  if (course.timeIsSample) {
    const sample = el('span', 'tag tag-sample', 'Sample time');
    sample.title = 'UTA does not publish meeting times the app can read yet, so this time slot is made up.';
    tags.append(sample);
  }
  if (course.custom) tags.append(el('span', 'tag', 'Custom'));
  info.append(tags);

  item.append(info);
  if (action) item.append(action);
  return item;
}

// ---------- The schedule ----------

function renderSchedule() {
  emptyNote.hidden = courses.length > 0;
  courseList.replaceChildren(...courses.map((course) => {
    const remove = el('button', 'btn-link', 'Remove');
    remove.type = 'button';
    remove.setAttribute('aria-label', `Remove ${courseTitle(course)}`);
    remove.addEventListener('click', () => {
      courses = courses.filter((c) => c.id !== course.id);
      markChanged();
      renderSchedule();
    });

    const item = courseItem(course, remove);
    const clashing = courses.filter((other) => other.id !== course.id && conflicts(course, other));
    if (clashing.length > 0) {
      item.querySelector('.course-info').append(
        el('p', 'course-warning', `Time conflict with ${clashing.map(shortName).join(', ')}`));
    }
    return item;
  }));
}

function addCourse(course) {
  if (!courses.some((c) => c.id === course.id)) {
    courses.push(course);
  }
  saveError.hidden = true;
  markChanged();
  renderSchedule();
  dialog.close();
}

// Hides "Schedule saved" once the schedule differs from what was saved.
function markChanged() {
  savedPanel.hidden = true;
}

function showEditMode(schedule) {
  scheduleId = schedule.id;
  builderHeading.textContent = 'Edit schedule';
  document.title = `${schedule.name} | CourseSweep`;
  // Keep the address pointing at this schedule, so a refresh reopens it.
  history.replaceState(null, '', `schedule.html?id=${schedule.id}`);
}

function showMissing(labels) {
  missingList.replaceChildren(...labels.map((label) => el('li', null, label)));
  missingPanel.hidden = labels.length === 0;
}

nameInput.addEventListener('input', () => {
  setFieldError(nameInput, '');
  markChanged();
});

saveBtn.addEventListener('click', async () => {
  const name = nameInput.value.trim();
  if (!name) {
    setFieldError(nameInput, 'Give your schedule a name.');
    nameInput.focus();
    return;
  }
  if (courses.length === 0) {
    saveError.textContent = 'Add at least one course before saving.';
    saveError.hidden = false;
    return;
  }

  const body = { name, courseIds: courses.map((c) => c.id) };
  const wasNew = scheduleId === null;
  let result;
  const ok = await submitWith(saveBtn, 'Saving…', saveError, async () => {
    // The first save creates the schedule; later saves update that same schedule.
    result = wasNew
      ? await Auth.request('POST', '/api/schedules', body)
      : await Auth.request('PUT', `/api/schedules/${scheduleId}`, body);
  });
  if (ok) {
    // submitWith only restores the button after a failure; here the page stays open for more edits.
    saveBtn.disabled = false;
    saveBtn.textContent = 'Save schedule';
    showEditMode(result);
    showMissing(result.missing);
    savedMessage.textContent = wasNew ? 'Schedule saved.' : 'Changes saved.';
    savedPanel.hidden = false;
  }
});

// ---------- Add course dialog ----------

function showView(name) {
  Object.entries(views).forEach(([key, view]) => { view.hidden = key !== name; });
  dialogTitle.textContent = viewTitles[name];
  dialogBack.hidden = name === 'choose';

  if (name === 'search') {
    searchInput.value = '';
    runSearch();
    searchInput.focus();
  } else if (name === 'custom') {
    customForm.reset();
    customError.hidden = true;
    [cName, cStart, cEnd, cRating].forEach((input) => setFieldError(input, ''));
    cDaysError.hidden = true;
    customSubmit.disabled = false;
    cName.focus();
  }
}

addCourseBtn.addEventListener('click', () => {
  showView('choose');
  dialog.showModal();
});
dialogBack.addEventListener('click', () => showView('choose'));
document.getElementById('dialog-close').addEventListener('click', () => dialog.close());
views.choose.querySelectorAll('.option').forEach((option) => {
  option.addEventListener('click', () => showView(option.dataset.view));
});

// ---------- Search ----------

let searchTimer = null;
let latestSearch = 0;

async function runSearch() {
  const searchId = ++latestSearch;
  let found;
  try {
    const query = encodeURIComponent(searchInput.value.trim());
    found = (await Auth.request('GET', `/api/courses?q=${query}`)).courses;
  } catch (err) {
    if (searchId !== latestSearch) return;
    searchResults.replaceChildren();
    searchStatus.textContent = err.message;
    searchStatus.hidden = false;
    return;
  }
  // Ignore a slow response that a newer search has already replaced.
  if (searchId !== latestSearch) return;

  searchStatus.textContent = 'No courses match that search.';
  searchStatus.hidden = found.length > 0;
  searchResults.replaceChildren(...found.map((course) => {
    const added = courses.some((c) => c.id === course.id);
    const add = el('button', 'btn-secondary', added ? 'Added' : 'Add');
    add.type = 'button';
    add.disabled = added;
    add.setAttribute('aria-label', `Add ${courseTitle(course)}`);
    add.addEventListener('click', () => addCourse(course));
    return courseItem(course, add);
  }));
}

searchInput.addEventListener('input', () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(runSearch, 200);
});

// ---------- Custom course ----------

clearErrorsOnInput(cName, cStart, cEnd, cRating);
dayBoxes.forEach((box) => box.addEventListener('change', () => { cDaysError.hidden = true; }));

customForm.addEventListener('submit', async (event) => {
  event.preventDefault();

  const days = dayBoxes.filter((box) => box.checked).map((box) => box.value);
  const ratingText = cRating.value.trim();
  const rating = ratingText === '' ? null : Number(ratingText);
  let firstInvalid = null;
  const fail = (input, message) => {
    setFieldError(input, message);
    firstInvalid = firstInvalid || input;
  };

  if (!cName.value.trim()) fail(cName, 'Enter a course name.');
  if (days.length === 0) {
    cDaysError.textContent = 'Choose at least one day.';
    cDaysError.hidden = false;
    firstInvalid = firstInvalid || dayBoxes[0];
  }
  if (!cStart.value) fail(cStart, 'Enter a start time.');
  if (!cEnd.value) fail(cEnd, 'Enter an end time.');
  else if (cStart.value && cEnd.value <= cStart.value) fail(cEnd, 'End time must be after the start time.');
  // A badInput check catches text the browser rejected, which reads back as an empty value.
  if (cRating.validity.badInput || (rating !== null && !(rating >= 1 && rating <= 5))) {
    fail(cRating, 'Rating must be a number from 1 to 5.');
  }
  if (firstInvalid) {
    firstInvalid.focus();
    return;
  }

  let created;
  const ok = await submitWith(customSubmit, 'Adding…', customError, async () => {
    created = await Auth.request('POST', '/api/courses', {
      name: cName.value.trim(),
      professor: cProfessor.value.trim() || null,
      days,
      start: cStart.value,
      end: cEnd.value,
      rating,
    });
  });
  if (ok) addCourse(created);
});

// ---------- Opening a saved schedule ----------

async function openSavedSchedule(id) {
  builder.hidden = true;
  builderHeading.textContent = 'Loading schedule…';
  try {
    const schedule = await Auth.request('GET', `/api/schedules/${encodeURIComponent(id)}`);
    showEditMode(schedule);
    nameInput.value = schedule.name;
    courses = schedule.courses;
    showMissing(schedule.missing);
    renderSchedule();
    builder.hidden = false;
  } catch (err) {
    builderHeading.textContent = 'Schedule not available';
    loadError.textContent = err.status === 404 ? 'This schedule was not found. It may have been deleted.' : err.message;
    loadError.hidden = false;
  }
}

const requestedId = new URLSearchParams(window.location.search).get('id');
if (requestedId) {
  openSavedSchedule(requestedId);
} else {
  renderSchedule();
}
