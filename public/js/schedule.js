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
const semesterSelect = document.getElementById('semester');
const addCourseBtn = document.getElementById('add-course');
const emptyNote = document.getElementById('empty-note');
const courseList = document.getElementById('course-list');
const saveBtn = document.getElementById('save-schedule');
const saveError = document.getElementById('save-error');
const savedPanel = document.getElementById('saved');
const savedMessage = document.getElementById('saved-message');

const addBlockBtn = document.getElementById('add-block');
const blocksEmpty = document.getElementById('blocks-empty');
const blockList = document.getElementById('block-list');
const findBestBtn = document.getElementById('find-best');
const bestError = document.getElementById('best-error');
const bestStatus = document.getElementById('best-status');
const bestList = document.getElementById('best-list');

const MAX_BLOCKS = 6;

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
const hideTaken = document.getElementById('hide-taken');
const canTake = document.getElementById('can-take');
const needed = document.getElementById('needed');
const searchStatus = document.getElementById('search-status');
const searchResults = document.getElementById('search-results');
const saveSelectedBtn = document.getElementById('save-selected');
const exitSearchBtn = document.getElementById('exit-search');

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

// The plan's courses, in the order they were added. Each is a section as the server sends it,
// plus two flags: anySection (the schedule finder may pick any section of its course) and
// chosen (this section was picked, rather than standing in for the course until the finder runs).
let courses = [];
// Courses whose "Sections and ratings" panel is open, by courseKey.
const openPanels = new Set();
// Id of the saved schedule being edited, or null for one not saved yet.
let scheduleId = null;
// Weekly times the student is busy, as { days, start, end }. 0 to MAX_BLOCKS of them.
let blocks = [];

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

// A custom course's rating is the student's own. A catalog section's overall rating averages the
// UTA Student Feedback Survey results for that instructor and course with CourseSweep students' ratings.
function ratingTag(course) {
  if (course.rating == null) {
    return el('span', 'tag', course.custom ? 'No rating' : 'No ratings yet');
  }
  if (course.custom) return el('span', 'tag', `★ ${course.rating.toFixed(1)} · your rating`);
  const parts = [];
  if (course.ratingResponses) parts.push(plural(course.ratingResponses, 'survey response'));
  if (course.studentRatings) parts.push(plural(course.studentRatings, 'student rating'));
  const tag = el('span', 'tag', `★ ${course.rating.toFixed(2)} overall · ${parts.join(' + ')}`);
  tag.title = 'Overall rating, 1 to 5: the UTA Student Feedback Survey average for this instructor and course'
    + (course.ratingTerms ? ` (${course.ratingTerms})` : '')
    + ", averaged with CourseSweep students' ratings. Each student rating counts the same as one survey response.";
  return tag;
}

function plural(count, word) {
  return `${count} ${word}${count === 1 ? '' : 's'}`;
}

// Sections of the same course share a key; a custom course is its own course.
function courseKey(course) {
  return course.catalogCourseId != null ? `course-${course.catalogCourseId}` : `custom-${course.id}`;
}

// A section in the plan has a real time once it was picked; until then it only stands for its course.
function hasFixedTime(course) {
  return !course.anySection || course.chosen;
}

// What the server needs to know about each course in the plan.
function planEntries() {
  return courses.map((c) => ({ id: c.id, anySection: Boolean(c.anySection), chosen: Boolean(c.chosen) }));
}

// ---------- Student ratings ----------

const RATING_CHOICES = ['1', '1.5', '2', '2.5', '3', '3.5', '4', '4.5', '5'];

// "Your rating" menu for one catalog section. Saving records it on the server, where it is averaged
// into the section's overall rating; the schedule finder uses it instead of the overall rating.
// onSaved gets the section with its new ratings.
function ratingPicker(section, onSaved) {
  const wrap = el('label', 'rating-pick');
  wrap.append('Your rating ');
  const select = el('select');
  select.append(new Option('Not rated', ''));
  const current = section.yourRating == null ? '' : String(section.yourRating);
  RATING_CHOICES.forEach((value) => select.append(new Option(`★ ${value}`, value)));
  if (current && !RATING_CHOICES.includes(current)) select.append(new Option(`★ ${current}`, current));
  select.value = current;
  const error = el('span', 'field-error');
  error.hidden = true;
  select.addEventListener('change', async () => {
    select.disabled = true;
    error.hidden = true;
    try {
      const updated = await Auth.request('PUT', '/api/ratings',
        { courseId: section.id, rating: select.value === '' ? null : Number(select.value) });
      courses.forEach((c) => {
        if (c.id === updated.id) {
          Object.assign(c, {
            rating: updated.rating, ratingResponses: updated.ratingResponses,
            studentRatings: updated.studentRatings, yourRating: updated.yourRating,
          });
        }
      });
      // Earlier results were ranked with the old rating.
      clearBest();
      onSaved(updated);
    } catch (err) {
      select.value = current;
      error.textContent = err.message;
      error.hidden = false;
      select.disabled = false;
    }
  });
  wrap.append(select, error);
  return wrap;
}

// One section: its professor and time, ratings, a "Your rating" menu, and the given buttons.
function sectionRow(section, actions, onRated) {
  const item = el('li', 'course section-row');
  const info = el('div', 'course-info');
  const professor = section.professor || 'Instructor not yet available';
  info.append(el('p', 'course-title', professor), el('p', 'course-meta', formatSlot(section)));
  const tags = el('p', 'course-tags');
  tags.append(ratingTag(section));
  if (section.timeIsSample) tags.append(sampleTag());
  info.append(tags, ratingPicker(section, onRated));
  item.append(info);
  const buttons = el('div', 'user-actions');
  buttons.append(...actions);
  item.append(buttons);
  return item;
}

function sampleTag() {
  const sample = el('span', 'tag tag-sample', 'Sample time');
  sample.title = 'UTA does not publish meeting times the app can read yet, so this time slot is made up.';
  return sample;
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
  if (course.timeIsSample) tags.append(sampleTag());
  if (course.custom) tags.append(el('span', 'tag', 'Custom'));
  info.append(tags);

  item.append(info);
  if (action) item.append(action);
  return item;
}

// ---------- The schedule ----------

function renderSchedule() {
  emptyNote.hidden = courses.length > 0;
  courseList.replaceChildren(...courses.map(planItem));
}

function sectionsText(count) {
  return plural(count, 'section');
}

// One course in the plan, with its "Sections and ratings" panel when open.
function planItem(course) {
  const key = courseKey(course);
  const item = el('li', 'course plan-entry');
  const info = el('div', 'course-info');
  info.append(el('p', 'course-title', courseTitle(course)));

  const professor = course.professor || (course.custom ? 'No professor listed' : 'Instructor not yet available');
  if (hasFixedTime(course)) {
    info.append(el('p', 'course-meta', `${professor} · ${formatSlot(course)}`));
  } else {
    info.append(el('p', 'course-meta', `Any of its ${sectionsText(course.sectionCount)}. Find best schedules picks one.`));
  }
  if (course.prerequisites) info.append(el('p', 'course-prereq', course.prerequisites));

  const tags = el('p', 'course-tags');
  if (course.custom) {
    tags.append(ratingTag(course), el('span', 'tag', 'Custom'));
  } else {
    if (hasFixedTime(course)) tags.append(ratingTag(course));
    if (course.yourRating != null && hasFixedTime(course)) tags.append(el('span', 'tag', `Your rating ★ ${course.yourRating}`));
    let mode = 'This section only';
    if (course.anySection) mode = course.chosen ? `Picked from ${sectionsText(course.sectionCount)}` : 'Any section';
    tags.append(el('span', 'tag tag-mode', mode));
    if (hasFixedTime(course) && course.timeIsSample) tags.append(sampleTag());
  }
  info.append(tags);

  // Only courses with a real time can clash.
  if (hasFixedTime(course)) {
    const clashing = courses.filter((other) => courseKey(other) !== key && hasFixedTime(other) && conflicts(course, other));
    if (clashing.length > 0) {
      info.append(el('p', 'course-warning', `Time conflict with ${clashing.map(shortName).join(', ')}`));
    }
    const busy = blocks.flatMap((block, i) => (blockIsValid(block) && conflicts(course, block) ? [i + 1] : []));
    if (busy.length > 0) info.append(el('p', 'course-warning', `Overlaps time block ${busy.join(', ')}`));
  }
  item.append(info);

  const actions = el('div', 'user-actions');
  if (!course.custom) {
    const open = openPanels.has(key);
    const toggle = el('button', 'btn-link', open ? 'Hide sections' : 'Sections and ratings');
    toggle.type = 'button';
    toggle.setAttribute('aria-expanded', String(open));
    toggle.addEventListener('click', () => {
      if (open) openPanels.delete(key);
      else openPanels.add(key);
      renderSchedule();
    });
    actions.append(toggle);
  }
  const remove = el('button', 'btn-link', 'Remove');
  remove.type = 'button';
  remove.setAttribute('aria-label', `Remove ${courseTitle(course)}`);
  remove.addEventListener('click', () => {
    courses = courses.filter((c) => courseKey(c) !== key);
    openPanels.delete(key);
    markChanged();
    clearBest();
    renderSchedule();
  });
  actions.append(remove);
  item.append(actions);

  if (openPanels.has(key)) item.append(sectionsPanel(course));
  return item;
}

// Every section of a plan course, each with "Your rating" and a way to keep only that section.
function sectionsPanel(entry) {
  const key = courseKey(entry);
  const panel = el('div', 'sections-panel');
  panel.append(el('p', 'card-text', 'Loading sections…'));
  Auth.request('GET', `/api/courses/${entry.id}/sections`).then(({ sections }) => {
    const children = [el('p', 'field-hint', entry.anySection
      ? 'Find best schedules tries every section below. Where you rate a section, your rating is used instead of the overall one.'
      : 'Only the section marked “In plan” is used. Where you rate a section, your rating is used instead of the overall one.')];
    if (!entry.anySection && sections.length > 1) {
      const any = el('button', 'btn-link', `Let CourseSweep pick from all ${sectionsText(sections.length)}`);
      any.type = 'button';
      any.addEventListener('click', () => {
        Object.assign(entry, { anySection: true, chosen: true });
        markChanged();
        clearBest();
        renderSchedule();
      });
      children.push(any);
    }
    const list = el('ul', 'section-list');
    sections.forEach((section) => {
      const action = !entry.anySection && entry.id === section.id
        ? el('span', 'tag', 'In plan')
        : el('button', 'btn-link', 'Use only this section');
      if (action.tagName === 'BUTTON') {
        action.type = 'button';
        action.addEventListener('click', () => setEntry(section, false));
      }
      list.append(sectionRow(section, [action], () => renderSchedule()));
    });
    children.push(list);
    panel.replaceChildren(...children);
  }).catch((err) => {
    if (courseKey(entry) === key) panel.replaceChildren(el('p', 'form-error', err.message));
  });
  return panel;
}

// ---------- Time blocks ----------

function blockIsValid(block) {
  return block.days.length > 0 && block.start && block.end && block.end > block.start;
}

function blockError(block) {
  if (block.days.length === 0) return 'Choose at least one day.';
  if (!block.start || !block.end) return 'Choose an hour and minute for the start and end time.';
  if (block.end <= block.start) return 'End time must be after the start time.';
  return '';
}

// Shows an error under each unfinished block. Returns true when every block is complete.
function validateBlocks() {
  let firstBad = null;
  blockList.querySelectorAll('.block').forEach((item, i) => {
    const message = blockError(blocks[i]);
    const error = item.querySelector('.field-error');
    error.textContent = message;
    error.hidden = !message;
    // Focus the part to fix: the days, or else the first time menu.
    if (message && !firstBad) firstBad = item.querySelector(blocks[i].days.length > 0 ? 'select' : 'input');
  });
  if (firstBad) firstBad.focus();
  return !firstBad;
}

function renderBlocks() {
  blocksEmpty.hidden = blocks.length > 0;
  addBlockBtn.disabled = blocks.length >= MAX_BLOCKS;
  addBlockBtn.textContent = blocks.length >= MAX_BLOCKS ? `${MAX_BLOCKS} time blocks (the most)` : '+ Add time block';

  blockList.replaceChildren(...blocks.map((block, index) => {
    const item = el('li', 'block');
    const head = el('div', 'block-head');
    head.append(el('p', 'block-title', `Time block ${index + 1}`));
    const remove = el('button', 'btn-link', 'Remove');
    remove.type = 'button';
    remove.setAttribute('aria-label', `Remove time block ${index + 1}`);
    remove.addEventListener('click', () => {
      blocks.splice(index, 1);
      blocksChanged();
      renderBlocks();
    });
    head.append(remove);
    item.append(head);

    const error = el('p', 'field-error');
    error.hidden = true;
    const clearError = () => { error.hidden = true; };

    const days = el('fieldset', 'field days');
    days.append(el('legend', null, 'Days'));
    const row = el('div', 'day-row');
    Object.entries(DAY_LABELS).forEach(([code, label]) => {
      const wrap = el('label');
      const box = el('input');
      box.type = 'checkbox';
      box.value = code;
      box.checked = block.days.includes(code);
      box.addEventListener('change', () => {
        // Keep the days in week order, as the server stores them.
        block.days = Object.keys(DAY_LABELS).filter((day) => (day === code ? box.checked : block.days.includes(day)));
        clearError();
        blocksChanged();
      });
      wrap.append(box, ` ${label}`);
      row.append(wrap);
    });
    days.append(row);
    item.append(days);

    const pair = el('div', 'field-pair');
    [['start', 'Start time'], ['end', 'End time']].forEach(([key, label]) => {
      pair.append(timePicker(label, block[key], (value) => {
        block[key] = value;
        clearError();
        blocksChanged();
      }));
    });
    item.append(pair, error);
    return item;
  }));
}

const HOURS = Array.from({ length: 12 }, (_, i) => String(i + 1));
const MINUTES = Array.from({ length: 12 }, (_, i) => String(i * 5).padStart(2, '0'));

function selectMenu(ariaLabel, placeholder, values, selected) {
  const select = el('select');
  select.setAttribute('aria-label', ariaLabel);
  if (placeholder) select.append(new Option(placeholder, ''));
  values.forEach((value) => select.append(new Option(value, value)));
  // A time saved before these menus existed may use a minute that is not a multiple of 5.
  if (selected && !values.includes(selected)) select.append(new Option(selected, selected));
  select.value = selected || '';
  return select;
}

// Hour (1-12), minute (00-55, every 5) and AM/PM menus for one time. onChange gets the
// time as 24-hour "HH:MM", the way the server stores it, or '' until hour and minute are chosen.
function timePicker(label, value, onChange) {
  let hour = '';
  let minute = '';
  let half = 'AM';
  if (value) {
    const [h, m] = value.split(':').map(Number);
    hour = String(h % 12 || 12);
    minute = String(m).padStart(2, '0');
    half = h < 12 ? 'AM' : 'PM';
  }

  const field = el('fieldset', 'field days time-field');
  field.append(el('legend', null, label));
  const row = el('div', 'time-pick');
  const hourMenu = selectMenu(`${label} hour`, 'Hour', HOURS, hour);
  const minuteMenu = selectMenu(`${label} minute`, 'Min', MINUTES, minute);
  const halfMenu = selectMenu(`${label} AM or PM`, null, ['AM', 'PM'], half);
  [hourMenu, minuteMenu, halfMenu].forEach((menu) => menu.addEventListener('change', () => {
    if (!hourMenu.value || !minuteMenu.value) {
      onChange('');
      return;
    }
    // 12 AM is midnight (00) and 12 PM is noon (12).
    const h = (Number(hourMenu.value) % 12) + (halfMenu.value === 'PM' ? 12 : 0);
    onChange(`${String(h).padStart(2, '0')}:${minuteMenu.value}`);
  }));
  row.append(hourMenu, minuteMenu, halfMenu);
  field.append(row);
  return field;
}

// Blocks changed: course warnings may differ, and earlier results no longer match.
function blocksChanged() {
  markChanged();
  clearBest();
  renderSchedule();
}

addBlockBtn.addEventListener('click', () => {
  if (blocks.length >= MAX_BLOCKS) return;
  blocks.push({ days: [], start: '', end: '' });
  blocksChanged();
  renderBlocks();
  blockList.lastElementChild.querySelector('input').focus();
});

// ---------- Best schedules ----------

function clearBest() {
  bestList.replaceChildren();
  bestStatus.hidden = true;
  bestError.hidden = true;
}

function formatGap(minutes) {
  if (minutes === 0) return 'no time between classes';
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  const text = [hours && `${hours} h`, rest && `${rest} min`].filter(Boolean).join(' ');
  return `${text} between classes each week`;
}

function sectionCount(n) {
  return `${n} section${n === 1 ? '' : 's'}`;
}

// Explains why no schedule was found, using the server's bottleneck report.
function noScheduleReason(found) {
  if (found.blockedCourses.length > 0) {
    const names = found.courses.filter((c) => c.fitting === 0)
      .map((c) => `${c.course} (${c.sections === 1 ? 'its only section' : `all ${c.sections} sections`})`);
    return `No schedule fits your time blocks. Every section of ${names.join(', ')} overlaps a time block. `
      + 'Change or remove a time block, or remove that course.';
  }
  if (found.conflictingPairs.length > 0) {
    const pairs = found.conflictingPairs.map(([a, b]) => `${a} and ${b}`);
    return `No schedule works: every section of ${pairs.join('; ')} meets at the same time. Remove one of each pair.`;
  }
  if (!found.complete) {
    return 'There were too many combinations to check them all, and none of the ones checked worked. Try fewer courses.';
  }
  return 'No combination of sections avoids time conflicts. Remove a course or a time block and try again.';
}

function renderBest(found) {
  if (found.schedules.length === 0) {
    bestError.textContent = noScheduleReason(found);
    bestError.hidden = false;
    return;
  }

  const shown = found.schedules.length;
  let status = `Found ${shown === 1 ? 'one schedule' : `the best ${shown} schedules`}.`;
  if (blocks.length > 0) {
    status += ' Sections outside your time blocks: '
      + found.courses.map((c) => `${c.course} ${c.fitting} of ${c.sections}`).join(', ') + '.';
  }
  if (!found.complete) {
    status += ' There were too many combinations to check them all, so these are the best of the ones checked.';
  }
  bestStatus.textContent = status;
  bestStatus.hidden = false;

  bestList.replaceChildren(...found.schedules.map((option, index) => {
    const item = el('li', 'best');
    const head = el('div', 'block-head');
    const info = el('div');
    info.append(el('p', 'block-title', `Option ${index + 1}`));
    const rating = option.averageRating == null ? 'No ratings' : `★ ${option.averageRating.toFixed(2)} average`;
    info.append(el('p', 'course-meta', `${rating} · ${formatGap(option.gapMinutes)}`));
    head.append(info);

    const use = el('button', 'btn-secondary', 'Use this schedule');
    use.type = 'button';
    use.addEventListener('click', () => {
      // Each course keeps how it was added; its section is now the one this option picked.
      courses = option.courses.map((section) => {
        const before = courses.find((c) => courseKey(c) === courseKey(section));
        return { ...section, anySection: before ? before.anySection : false, chosen: true };
      });
      markChanged();
      renderSchedule();
      bestList.querySelectorAll('.best').forEach((other) => other.classList.remove('best-chosen'));
      item.classList.add('best-chosen');
      bestList.querySelectorAll('.best button').forEach((btn) => { btn.textContent = 'Use this schedule'; });
      use.textContent = 'In your schedule';
      courseList.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
    head.append(use);
    item.append(head);

    const lines = el('ul', 'best-courses');
    option.courses.forEach((course) => {
      const professor = course.professor || (course.custom ? 'No professor listed' : 'Instructor not yet available');
      let stars = course.rating == null ? '' : ` · ★ ${course.rating.toFixed(2)}`;
      if (course.yourRating != null) stars = ` · ★ ${course.yourRating} (your rating)`;
      const line = el('li');
      line.append(el('strong', null, shortName(course)), ` · ${professor} · ${formatSlot(course)}${stars}`);
      lines.append(line);
    });
    item.append(lines);
    return item;
  }));
}

findBestBtn.addEventListener('click', async () => {
  clearBest();
  if (courses.length === 0) {
    bestError.textContent = 'Add at least one course first.';
    bestError.hidden = false;
    return;
  }
  if (!validateBlocks()) return;

  let found;
  const ok = await submitWith(findBestBtn, 'Finding…', bestError, async () => {
    found = await Auth.request('POST', '/api/schedule-options', { entries: planEntries(), blocks });
  });
  if (ok) {
    findBestBtn.disabled = false;
    findBestBtn.textContent = 'Find best schedules';
    renderBest(found);
  }
});

// Puts a course in the plan, or changes how it is in the plan: anySection lets the schedule finder
// pick any of its sections, otherwise only this section is used. A course is in the plan once.
function setEntry(course, anySection) {
  const any = anySection && !course.custom;
  const entry = { ...course, anySection: any, chosen: !any };
  const index = courses.findIndex((c) => courseKey(c) === courseKey(course));
  if (index >= 0) courses[index] = entry;
  else courses.push(entry);
  saveError.hidden = true;
  markChanged();
  clearBest();
  renderSchedule();
}

function addCourse(course, anySection) {
  setEntry(course, anySection);
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

semesterSelect.addEventListener('change', () => {
  setFieldError(semesterSelect, '');
  markChanged();
});

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
  if (!semesterSelect.value) {
    setFieldError(semesterSelect, 'Choose the semester you are enrolling in.');
    semesterSelect.focus();
    return;
  }
  if (courses.length === 0) {
    saveError.textContent = 'Add at least one course before saving.';
    saveError.hidden = false;
    return;
  }
  if (!validateBlocks()) return;

  const body = { name, semester: semesterSelect.value, entries: planEntries(), blocks };
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
    // With hideTaken the server leaves out courses marked as taken on the My degree page;
    // with canTake, courses whose prerequisites are not among them; with needed, courses
    // that fill no open requirement of the major chosen there.
    found = await Auth.request('GET', `/api/courses?q=${query}&hideTaken=${hideTaken.checked}`
      + `&canTake=${canTake.checked}&needed=${needed.checked}`);
  } catch (err) {
    if (searchId !== latestSearch) return;
    searchResults.replaceChildren();
    searchStatus.textContent = err.message;
    searchStatus.hidden = false;
    return;
  }
  // Ignore a slow response that a newer search has already replaced.
  if (searchId !== latestSearch) return;

  const sections = (n) => `${n} section${n === 1 ? '' : 's'}`;
  let hidden = '';
  if (found.hiddenTaken > 0) {
    hidden += ` ${sections(found.hiddenTaken)} of courses you have taken ${found.hiddenTaken === 1 ? 'is' : 'are'} hidden.`;
  }
  if (found.hiddenNotReady > 0) {
    hidden += ` ${sections(found.hiddenNotReady)} of courses whose prerequisites you have not taken `
      + `${found.hiddenNotReady === 1 ? 'is' : 'are'} hidden.`;
  }
  if (found.hiddenNotNeeded > 0) {
    hidden += ` ${sections(found.hiddenNotNeeded)} of courses your degree does not need `
      + `${found.hiddenNotNeeded === 1 ? 'is' : 'are'} hidden.`;
  }
  searchStatus.textContent = found.courses.length > 0 ? hidden.trim() : `No courses match that search.${hidden}`;
  searchStatus.hidden = searchStatus.textContent === '';
  renderResults(found.courses);
}

// Results grouped by course: each catalog course can be selected with all its sections,
// or one section at a time. A custom course is one row.
function renderResults(sections) {
  shownSections = sections;
  updateSearchActions();
  const groups = [];
  sections.forEach((section) => {
    const key = courseKey(section);
    let group = groups.find((g) => g.key === key);
    if (!group) {
      group = { key, sections: [] };
      groups.push(group);
    }
    group.sections.push(section);
  });
  searchResults.replaceChildren(...groups.map((group) => (group.sections[0].custom
    ? customResult(group.sections[0]) : courseResult(group))));
}

// ---------- Selecting courses in search ----------
//
// Search only selects courses. They are added to the plan when the student clicks
// "Save selected courses", and dropped by "Exit without saving" (or closing the dialog).

// courseKey -> { course, anySection }: one selection per course.
const selected = new Map();
// The sections last shown, so the results can be redrawn as selections change.
let shownSections = [];

function isSelected(course, anySection) {
  const pick = selected.get(courseKey(course));
  return Boolean(pick) && pick.anySection === anySection && (anySection || pick.course.id === course.id);
}

// Selecting again clears it. Picking the whole course replaces picking one of its sections, and the other way round.
function toggleSelected(course, anySection) {
  const key = courseKey(course);
  if (isSelected(course, anySection)) selected.delete(key);
  else selected.set(key, { course, anySection });
  renderResults(shownSections);
}

function updateSearchActions() {
  const count = selected.size;
  saveSelectedBtn.disabled = count === 0;
  saveSelectedBtn.textContent = count === 0 ? 'Save selected courses'
    : `Save ${count} selected course${count === 1 ? '' : 's'}`;
}

// A select button: shows "Selected" when picked, and "In your plan" when the plan already has it that way.
function selectButton(className, label, course, anySection, inPlan, ariaLabel) {
  const button = el('button', className);
  button.type = 'button';
  const picked = isSelected(course, anySection);
  button.textContent = inPlan ? 'In your plan' : picked ? '✓ Selected' : label;
  button.disabled = inPlan;
  button.classList.toggle('is-selected', picked);
  button.setAttribute('aria-pressed', String(picked));
  button.setAttribute('aria-label', ariaLabel);
  button.addEventListener('click', () => toggleSelected(course, anySection));
  return button;
}

saveSelectedBtn.addEventListener('click', () => {
  selected.forEach(({ course, anySection }) => setEntry(course, anySection));
  dialog.close();
});
exitSearchBtn.addEventListener('click', () => dialog.close());
// However the dialog closes, unsaved selections are dropped.
dialog.addEventListener('close', () => {
  selected.clear();
  updateSearchActions();
});

function customResult(course) {
  const inPlan = courses.some((c) => c.id === course.id);
  return courseItem(course, selectButton('btn-secondary', 'Select', course, false, inPlan,
    `Select ${courseTitle(course)}`));
}

function courseResult(group) {
  const first = group.sections[0];
  const item = el('li', 'course-group');
  const head = el('div', 'course-group-head');
  const info = el('div', 'course-info');
  info.append(el('p', 'course-title', courseTitle(first)));
  const shown = group.sections.length;
  info.append(el('p', 'course-meta', shown === first.sectionCount ? sectionsText(first.sectionCount)
    : `${sectionsText(first.sectionCount)} · ${shown} ${shown === 1 ? 'matches' : 'match'} your search`));
  if (first.prerequisites) info.append(el('p', 'course-prereq', first.prerequisites));
  head.append(info);

  const entry = courses.find((c) => courseKey(c) === group.key);
  head.append(selectButton('btn-secondary', `Select course (all ${sectionsText(first.sectionCount)})`,
    first, true, Boolean(entry && entry.anySection), `Select ${courseTitle(first)} with all its sections`));
  item.append(head);

  // The sections sit in their own box inside the course's box.
  const box = el('div', 'sections-box');
  box.append(el('p', 'sections-box-label', shown === 1 ? 'Or pick this section only:' : 'Or pick one section only:'));
  const list = el('ul', 'section-list');
  group.sections.forEach((section) => list.append(searchSectionRow(section)));
  box.append(list);
  item.append(box);
  return item;
}

function searchSectionRow(section) {
  const inPlan = courses.some((c) => c.id === section.id && !c.anySection);
  const select = selectButton('btn-link', 'Select only this section', section, false, inPlan,
    `Select only the ${section.professor || 'unlisted instructor'} section of ${shortName(section)}`);
  const row = sectionRow(section, [select], (updated) => {
    // Keep a selection pointing at the section with its new rating.
    const pick = selected.get(courseKey(updated));
    if (pick && pick.course.id === updated.id) pick.course = updated;
    shownSections = shownSections.map((s) => (s.id === updated.id ? updated : s));
    row.replaceWith(searchSectionRow(updated));
  });
  return row;
}

hideTaken.addEventListener('change', runSearch);
canTake.addEventListener('change', runSearch);
needed.addEventListener('change', runSearch);
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
  if (ok) addCourse(created, false);
});

// ---------- Opening a saved schedule ----------

async function openSavedSchedule(id) {
  builder.hidden = true;
  builderHeading.textContent = 'Loading schedule…';
  try {
    const schedule = await Auth.request('GET', `/api/schedules/${encodeURIComponent(id)}`);
    showEditMode(schedule);
    nameInput.value = schedule.name;
    // Plans saved before semesters existed have none; the student picks one before saving again.
    semesterSelect.value = schedule.semester || '';
    courses = schedule.courses;
    blocks = schedule.blocks;
    showMissing(schedule.missing);
    renderBlocks();
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
  renderBlocks();
  renderSchedule();
}
