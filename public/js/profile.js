// My degree: the student's college and major, a checklist of the catalog
// courses they have already taken, and how many classes they still need.
// The server stores the checked courses as a BitSet; this page only ever
// sends and receives course codes.

const profileError = document.getElementById('profile-error');
const profileLoading = document.getElementById('profile-loading');
const profileForm = document.getElementById('profile-form');
const collegeSelect = document.getElementById('college');
const degreeSelect = document.getElementById('degree');
const degreeInfo = document.getElementById('degree-info');
const takenCount = document.getElementById('taken-count');
const autoNote = document.getElementById('auto-note');
const courseFilter = document.getElementById('course-filter');
const courseGroups = document.getElementById('course-groups');
const noMatches = document.getElementById('no-matches');
const saveStatus = document.getElementById('save-status');
const saveBtn = document.getElementById('save-profile');

const progressEmpty = document.getElementById('progress-empty');
const progressBody = document.getElementById('progress-body');
const progressClasses = document.getElementById('progress-classes');
const progressClassesLabel = document.getElementById('progress-classes-label');
const progressBar = document.getElementById('progress-bar');
const progressFill = document.getElementById('progress-fill');
const progressHours = document.getElementById('progress-hours');
const progressSummary = document.getElementById('progress-summary');
const progressList = document.getElementById('progress-list');
const progressNotListed = document.getElementById('progress-not-listed');

let colleges = [];
let catalog = [];             // every course of the selected college: { code, title, hours, requires }
const requiresOf = new Map(); // code -> codes it certainly requires (directly or through a chain)
const taken = new Set();      // codes of the courses checked as taken
const boxes = new Map();      // code -> its checkbox

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function plural(count, word, many = `${word}s`) {
  return `${count} ${count === 1 ? word : many}`;
}

function showError(message) {
  profileError.textContent = message;
  profileError.hidden = false;
}

// 3000- and 4000-level courses are the professional program.
function isProfessional(code) {
  return Number(code.split(' ')[1]) >= 3000;
}

function markChanged() {
  saveStatus.hidden = true;
  takenCount.textContent = `${taken.size} marked as taken`;
  scheduleProgress();
}

function fillDegrees(college, selectedId) {
  degreeSelect.replaceChildren(el('option', null, 'Choose your major'));
  degreeSelect.firstChild.value = '';
  college.degrees.forEach((degree) => {
    const option = el('option', null, `${degree.name} (${degree.award})`);
    option.value = String(degree.id);
    option.selected = degree.id === selectedId;
    degreeSelect.append(option);
  });
}

// ---------- Checklist ----------

// Returns [{ name, courses }] in display order. With a major the groups follow the degree
// plan; without one they are by subject. Either way every 3000- and 4000-level course comes
// first, then the 1000- and 2000-level ones, and each course appears once.
async function buildGroups() {
  const groups = [];
  const degreeId = Number(degreeSelect.value);
  if (degreeId) {
    const degree = await Auth.request('GET', `/api/degrees/${degreeId}`);
    degreeInfo.textContent = `${degree.college} ${degree.catalogYear} catalog · ${degree.totalHours} hours. `
      + 'Courses are grouped as in the degree plan.';
    degreeInfo.hidden = false;
    const seen = new Set();
    degree.requirements.forEach((requirement) => {
      const name = requirement.chooseHours
        ? `${requirement.section}: choose ${requirement.chooseHours} hours` : requirement.section;
      let group = groups.find((g) => g.name === name);
      if (!group) {
        group = { name, courses: [] };
        groups.push(group);
      }
      requirement.options.flat().forEach((course) => {
        if (!seen.has(course.code)) {
          seen.add(course.code);
          group.courses.push(course);
        }
      });
    });
    groups.push({ name: 'Other courses', courses: catalog.filter((course) => !seen.has(course.code)) });
  } else {
    degreeInfo.hidden = true;
    catalog.forEach((course) => {
      const name = course.code.split(' ')[0];
      let group = groups.find((g) => g.name === name);
      if (!group) {
        group = { name, courses: [] };
        groups.push(group);
      }
      group.courses.push(course);
    });
  }
  const split = (professional) => groups
    .map((g) => ({ name: g.name, courses: g.courses.filter((c) => isProfessional(c.code) === professional) }))
    .filter((g) => g.courses.length > 0);
  return { professional: split(true), foundation: split(false) };
}

function courseRow(course) {
  const label = el('label', 'check-row');
  const box = el('input');
  box.type = 'checkbox';
  box.value = course.code;
  box.checked = taken.has(course.code);
  box.addEventListener('change', () => toggle(course.code, box.checked));
  boxes.set(course.code, box);
  label.append(box, el('span', 'check-code', course.code), el('span', 'check-title', course.title),
    el('span', 'check-hours', `${course.hours} hr${course.hours === '1' ? '' : 's'}`));
  return label;
}

function block(title, groups) {
  const wrap = el('div', 'check-block');
  wrap.append(el('h3', 'check-block-title', title));
  groups.forEach(({ name, courses }) => {
    const fieldset = el('fieldset', 'check-group');
    fieldset.append(el('legend', null, name));
    courses.forEach((course) => fieldset.append(courseRow(course)));
    wrap.append(fieldset);
  });
  return wrap;
}

async function renderCourses() {
  let groups;
  try {
    groups = await buildGroups();
  } catch (err) {
    showError(err.message);
    return;
  }
  boxes.clear();
  courseGroups.replaceChildren(
    block('Professional courses (3000 and 4000 level)', groups.professional),
    block('Foundation courses (1000 and 2000 level)', groups.foundation),
  );
  applyFilter();
  scheduleProgress();
}

// Checking a course checks everything it requires; unchecking one unchecks everything
// that requires it. Either way the list stays possible: no course is taken without its
// prerequisites. The server applies the same rule when saving.
function toggle(code, checked) {
  const changed = [];
  if (checked) {
    taken.add(code);
    (requiresOf.get(code) || []).forEach((needed) => {
      if (!taken.has(needed)) {
        taken.add(needed);
        changed.push(needed);
      }
    });
  } else {
    taken.delete(code);
    [...taken].forEach((other) => {
      if ((requiresOf.get(other) || []).includes(code)) {
        taken.delete(other);
        changed.push(other);
      }
    });
  }
  changed.forEach((other) => {
    if (boxes.has(other)) boxes.get(other).checked = checked;
  });
  if (changed.length > 0) {
    autoNote.textContent = checked
      ? `Also checked ${changed.sort().join(', ')}, because ${code} requires ${changed.length === 1 ? 'it' : 'them'}.`
      : `Also unchecked ${changed.sort().join(', ')}, because ${changed.length === 1 ? 'it requires' : 'they require'} ${code}.`;
    autoNote.hidden = false;
  } else {
    autoNote.hidden = true;
  }
  markChanged();
}

// Hides rows that do not match the search. It never changes which boxes are checked.
function applyFilter() {
  const query = courseFilter.value.trim().toLowerCase().replace(/\s+/g, ' ');
  let shown = 0;
  courseGroups.querySelectorAll('.check-block').forEach((section) => {
    let sectionShown = 0;
    section.querySelectorAll('.check-group').forEach((group) => {
      let groupShown = 0;
      group.querySelectorAll('.check-row').forEach((row) => {
        const text = row.textContent.toLowerCase();
        const match = !query || text.includes(query) || text.replace(/ /g, '').includes(query.replace(/ /g, ''));
        row.hidden = !match;
        if (match) groupShown++;
      });
      group.hidden = groupShown === 0;
      sectionShown += groupShown;
    });
    section.hidden = sectionShown === 0;
    shown += sectionShown;
  });
  noMatches.hidden = shown > 0;
}

// ---------- Classes left to graduate ----------

let progressTimer = null;
let latestProgress = 0;

// Waits for a pause in checking boxes, then asks the server. Only the newest answer is shown.
function scheduleProgress() {
  clearTimeout(progressTimer);
  progressTimer = setTimeout(updateProgress, 250);
}

async function updateProgress() {
  const degreeId = Number(degreeSelect.value);
  if (!degreeId) {
    progressEmpty.hidden = false;
    progressBody.hidden = true;
    return;
  }
  const requestId = ++latestProgress;
  let progress;
  try {
    progress = await Auth.request('POST', '/api/progress', { degreeId, completed: [...taken] });
  } catch (err) {
    if (requestId === latestProgress) showError(err.message);
    return;
  }
  if (requestId !== latestProgress) return;
  renderProgress(progress);
}

function renderProgress(p) {
  progressEmpty.hidden = true;
  progressBody.hidden = false;
  progressClasses.textContent = p.classesRemaining === 0 ? 'Done' : plural(p.classesRemaining, 'class', 'classes');
  progressClassesLabel.textContent = p.classesRemaining === 0
    ? `You have every course listed in the ${p.degree} plan.`
    : `left in the ${p.degree} plan`;

  const percent = p.listedHours ? Math.round((p.listedHoursDone / p.listedHours) * 100) : 0;
  progressFill.style.width = `${percent}%`;
  progressBar.setAttribute('aria-valuemax', String(p.listedHours));
  progressBar.setAttribute('aria-valuenow', String(p.listedHoursDone));
  progressHours.textContent = `${p.listedHoursDone} of ${p.listedHours} hours of listed courses done (${percent}%) · `
    + `${p.requirementsDone} of ${p.requirementsTotal} requirements met`;

  progressSummary.textContent = p.remaining.length === 0 ? 'Nothing left in the listed courses'
    : `What is left (${plural(p.remaining.length, 'requirement')})`;
  progressList.replaceChildren(...p.remaining.map((item) => {
    const li = el('li');
    li.append(el('span', 'progress-section', item.section), el('span', null, ` ${item.needs}`),
      el('span', 'progress-classes', ` · ${plural(item.classes, 'class', 'classes')}`));
    return li;
  }));

  progressNotListed.textContent = p.notListedHours === 0 ? '' : `Not counted above: ${p.notListedHours} more hours the `
    + `plan does not list by course (${p.notListed.map((n) => `${n.name.replace('Core curriculum: ', '')} ${n.hours}`).join(', ')}). `
    + 'CourseSweep cannot tell whether you have taken these yet.';
}

// ---------- Loading and saving ----------

async function loadCollege(college, selectedDegreeId) {
  fillDegrees(college, selectedDegreeId);
  catalog = (await Auth.request('GET', `/api/colleges/${college.id}/courses`)).courses;
  requiresOf.clear();
  catalog.forEach((course) => requiresOf.set(course.code, course.requires));
  await renderCourses();
}

async function load() {
  try {
    colleges = (await Auth.request('GET', '/api/colleges')).colleges;
    const profile = await Auth.request('GET', '/api/profile');
    profile.completed.forEach((code) => taken.add(code));

    // The saved major's college, or else the default college (UTA).
    const college = colleges.find((c) => c.degrees.some((d) => d.id === profile.degreeId))
      || colleges.find((c) => c.default) || colleges[0];
    collegeSelect.replaceChildren(...colleges.map((c) => {
      const option = el('option', null, c.name);
      option.value = String(c.id);
      option.selected = c.id === college.id;
      return option;
    }));
    await loadCollege(college, profile.degreeId);
    markChanged();
    saveStatus.hidden = true;
    profileLoading.hidden = true;
    profileForm.hidden = false;
  } catch (err) {
    profileLoading.hidden = true;
    showError(err.message);
  }
}

collegeSelect.addEventListener('change', () => {
  const college = colleges.find((c) => String(c.id) === collegeSelect.value);
  markChanged();
  loadCollege(college, null).catch((err) => showError(err.message));
});
degreeSelect.addEventListener('change', () => {
  markChanged();
  renderCourses();
});
courseFilter.addEventListener('input', applyFilter);

saveBtn.addEventListener('click', async () => {
  const ok = await submitWith(saveBtn, 'Saving…', profileError, () => Auth.request('PUT', '/api/profile', {
    degreeId: degreeSelect.value ? Number(degreeSelect.value) : null,
    completed: [...taken],
  }));
  if (ok) {
    saveBtn.disabled = false;
    saveBtn.textContent = 'Save';
    saveStatus.hidden = false;
  }
});

load();
