// My schedules: lists the signed-in user's saved schedules, most recently
// saved first, with Open, Rename and Delete for each.

const listError = document.getElementById('list-error');
const listStatus = document.getElementById('list-status');
const listLoading = document.getElementById('list-loading');
const listEmpty = document.getElementById('list-empty');
const scheduleList = document.getElementById('schedule-list');

let schedules = [];
// The schedule whose name is being edited, if any.
let renamingId = null;

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function plural(count, word) {
  return `${count} ${word}${count === 1 ? '' : 's'}`;
}

function formatDate(seconds) {
  return new Date(seconds * 1000).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

function showError(message) {
  listStatus.hidden = true;
  listError.textContent = message;
  listError.hidden = false;
}

function showStatus(message) {
  listError.hidden = true;
  listStatus.textContent = message;
  listStatus.hidden = false;
}

async function loadSchedules() {
  try {
    schedules = (await Auth.request('GET', '/api/schedules')).schedules;
    listLoading.hidden = true;
    render();
  } catch (err) {
    listLoading.hidden = true;
    showError(err.message);
  }
}

function button(label, className, onClick) {
  const node = el('button', className, label);
  node.type = 'button';
  node.addEventListener('click', onClick);
  return node;
}

function scheduleItem(schedule) {
  const item = el('li', 'course user-row');

  if (renamingId === schedule.id) {
    item.append(renameForm(schedule));
    return item;
  }

  const info = el('div', 'course-info');
  const title = el('a', null, schedule.name);
  title.href = `schedule.html?id=${schedule.id}`;
  const titleRow = el('p', 'course-title');
  titleRow.append(title);
  info.append(titleRow);
  info.append(el('p', 'course-meta',
    `${schedule.semester || 'No semester chosen'} · ${plural(schedule.courseCount, 'course')}`
    + ` · Saved ${formatDate(schedule.updatedAt)}`));
  if (schedule.missingCount > 0) {
    info.append(el('p', 'course-warning',
      `${plural(schedule.missingCount, 'course')} in this schedule ${schedule.missingCount === 1 ? 'is' : 'are'} `
      + 'no longer available. Open it to see which.'));
  }
  item.append(info);

  const actions = el('div', 'user-actions');
  const open = el('a', 'btn-link', 'Open');
  open.href = `schedule.html?id=${schedule.id}`;
  open.setAttribute('aria-label', `Open ${schedule.name}`);
  const rename = button('Rename', 'btn-link', () => {
    renamingId = schedule.id;
    render();
    document.getElementById('rename-input').focus();
  });
  rename.setAttribute('aria-label', `Rename ${schedule.name}`);
  const remove = button('Delete', 'btn-link btn-danger', () => deleteSchedule(schedule));
  remove.setAttribute('aria-label', `Delete ${schedule.name}`);
  actions.append(open, rename, remove);
  item.append(actions);
  return item;
}

function renameForm(schedule) {
  const form = el('form', 'rename-form');
  form.noValidate = true;

  const field = el('div', 'field');
  const label = el('label', null, `New name for “${schedule.name}”`);
  label.htmlFor = 'rename-input';
  const input = el('input');
  input.type = 'text';
  input.id = 'rename-input';
  input.maxLength = 100;
  input.value = schedule.name;
  input.setAttribute('aria-describedby', 'rename-input-error');
  const error = el('p', 'field-error');
  error.id = 'rename-input-error';
  error.hidden = true;
  field.append(label, input, error);
  input.addEventListener('input', () => setFieldError(input, ''));

  const save = el('button', 'btn-secondary', 'Save name');
  save.type = 'submit';
  const cancel = button('Cancel', 'btn-link', () => {
    renamingId = null;
    render();
  });
  const actions = el('div', 'user-actions');
  actions.append(save, cancel);
  form.append(field, actions);

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const name = input.value.trim();
    if (!name) {
      setFieldError(input, 'Enter a name.');
      input.focus();
      return;
    }
    save.disabled = true;
    try {
      await Auth.request('PATCH', `/api/schedules/${schedule.id}`, { name });
      renamingId = null;
      showStatus(`Renamed to “${name}”.`);
      await loadSchedules();
    } catch (err) {
      setFieldError(input, err.message);
      save.disabled = false;
    }
  });
  return form;
}

async function deleteSchedule(schedule) {
  const sure = window.confirm(`Delete “${schedule.name}”?\n\nThis cannot be undone.`);
  if (!sure) return;
  try {
    await Auth.request('DELETE', `/api/schedules/${schedule.id}`);
    showStatus(`Deleted “${schedule.name}”.`);
  } catch (err) {
    showError(err.message);
  }
  await loadSchedules();
}

function render() {
  listEmpty.hidden = schedules.length > 0;
  scheduleList.replaceChildren(...schedules.map(scheduleItem));
}

loadSchedules();
