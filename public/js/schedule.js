// Schedule builder: a named list of courses, added either from the catalog
// or as custom courses, then saved to the server.

const DAY_LABELS = { Mo: 'Mon', Tu: 'Tue', We: 'Wed', Th: 'Thu', Fr: 'Fri', Sa: 'Sat', Su: 'Sun' };

const nameInput = document.getElementById('schedule-name');
const addCourseBtn = document.getElementById('add-course');
const emptyNote = document.getElementById('empty-note');
const courseList = document.getElementById('course-list');
const saveBtn = document.getElementById('save-schedule');
const saveError = document.getElementById('save-error');
const savedPanel = document.getElementById('saved');

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
let saved = false;

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

// Builds one course row; `action` is the button shown on the right, if any.
function courseItem(course, action) {
  const item = el('li', 'course');
  const info = el('div', 'course-info');
  info.append(el('p', 'course-title', courseTitle(course)));

  const details = [course.professor || 'No professor listed', formatSlot(course)];
  info.append(el('p', 'course-meta', details.join(' · ')));

  const tags = el('p', 'course-tags');
  tags.append(el('span', 'tag', course.rating == null ? 'No rating' : `★ ${course.rating.toFixed(1)}`));
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
    let remove = null;
    if (!saved) {
      remove = el('button', 'btn-link', 'Remove');
      remove.type = 'button';
      remove.setAttribute('aria-label', `Remove ${courseTitle(course)}`);
      remove.addEventListener('click', () => {
        courses = courses.filter((c) => c.id !== course.id);
        renderSchedule();
      });
    }

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
  renderSchedule();
  dialog.close();
}

nameInput.addEventListener('input', () => setFieldError(nameInput, ''));

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

  const ok = await submitWith(saveBtn, 'Saving…', saveError, () =>
    Auth.request('POST', '/api/schedules', { name, courseIds: courses.map((c) => c.id) }));
  if (ok) {
    // Lock the page so the saved schedule cannot be edited into a duplicate.
    saved = true;
    nameInput.disabled = true;
    addCourseBtn.hidden = true;
    saveBtn.hidden = true;
    savedPanel.hidden = false;
    renderSchedule();
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

renderSchedule();
