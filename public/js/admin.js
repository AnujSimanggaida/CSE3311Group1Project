// Admin page: lists every account and lets admins grant or remove admin,
// sign a user out everywhere, or delete an account. The server checks admin
// access on every request; hiding the page from non-admins is only cosmetic.

const panel = document.getElementById('admin-panel');
const adminError = document.getElementById('admin-error');
const adminStatus = document.getElementById('admin-status');
const filterInput = document.getElementById('user-filter');
const userList = document.getElementById('user-list');
const userCount = document.getElementById('user-count');
const noUsers = document.getElementById('no-users');

let users = [];

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function plural(count, word) {
  return `${count} ${word}${count === 1 ? '' : 's'}`;
}

function showError(message) {
  adminStatus.hidden = true;
  adminError.textContent = message;
  adminError.hidden = false;
}

function showStatus(message) {
  adminError.hidden = true;
  adminStatus.textContent = message;
  adminStatus.hidden = false;
}

async function loadUsers() {
  try {
    users = (await Auth.request('GET', '/api/admin/users')).users;
    panel.hidden = false;
    renderUsers();
  } catch (err) {
    panel.hidden = true;
    showError(err.status === 403 ? 'You do not have admin access.' : err.message);
  }
}

// Runs one admin action, then reloads the list so it matches the server.
async function runAction(user, path, body, doneMessage) {
  try {
    await Auth.request('POST', path, { id: user.id, ...body });
    showStatus(doneMessage);
  } catch (err) {
    showError(err.message);
  }
  await loadUsers();
}

function actionButton(label, user, onClick, danger) {
  const button = el('button', danger ? 'btn-link btn-danger' : 'btn-link', label);
  button.type = 'button';
  button.setAttribute('aria-label', `${label}: ${user.email}`);
  button.addEventListener('click', onClick);
  return button;
}

function userItem(user) {
  const item = el('li', 'course user-row');
  const info = el('div', 'course-info');
  info.append(el('p', 'course-title', user.email));

  const joined = new Date(user.createdAt * 1000).toLocaleDateString();
  info.append(el('p', 'course-meta', [
    `Joined ${joined}`,
    plural(user.schedules, 'schedule'),
    plural(user.customCourses, 'custom course'),
    user.activeSessions > 0 ? `signed in on ${plural(user.activeSessions, 'device')}` : 'signed out',
  ].join(' · ')));

  const tags = el('p', 'course-tags');
  if (user.you) tags.append(el('span', 'tag', 'You'));
  if (user.admin) tags.append(el('span', 'tag tag-admin', user.configuredAdmin ? 'Admin (set in config)' : 'Admin'));
  if (user.google) tags.append(el('span', 'tag', 'Google'));
  if (tags.childElementCount > 0) info.append(tags);
  item.append(info);

  // Changing or deleting your own account here could lock you out, and the
  // server refuses it anyway. Config admins can only be changed in the config.
  if (user.you) return item;

  const actions = el('div', 'user-actions');
  if (!user.configuredAdmin) {
    actions.append(user.admin
      ? actionButton('Remove admin', user, () => runAction(user, '/api/admin/users/set-admin',
        { admin: false }, `${user.email} is no longer an admin.`))
      : actionButton('Make admin', user, () => runAction(user, '/api/admin/users/set-admin',
        { admin: true }, `${user.email} is now an admin.`)));
  }
  if (user.activeSessions > 0) {
    actions.append(actionButton('Sign out everywhere', user, () => runAction(user, '/api/admin/users/sign-out',
      {}, `${user.email} was signed out on every device.`)));
  }
  if (!user.configuredAdmin) {
    actions.append(actionButton('Delete', user, () => {
      const sure = window.confirm(`Delete ${user.email}?\n\nThis also deletes their `
        + `${plural(user.schedules, 'schedule')} and ${plural(user.customCourses, 'custom course')}. `
        + 'It cannot be undone.');
      if (sure) runAction(user, '/api/admin/users/delete', {}, `${user.email} was deleted.`);
    }, true));
  }
  item.append(actions);
  return item;
}

function renderUsers() {
  const query = filterInput.value.trim().toLowerCase();
  const shown = users.filter((user) => user.email.includes(query));
  userCount.textContent = query ? `Users (${shown.length} of ${users.length})` : `Users (${users.length})`;
  noUsers.hidden = shown.length > 0;
  userList.replaceChildren(...shown.map(userItem));
}

filterInput.addEventListener('input', renderUsers);
document.getElementById('refresh').addEventListener('click', () => {
  adminError.hidden = true;
  adminStatus.hidden = true;
  loadUsers();
});

loadUsers();
