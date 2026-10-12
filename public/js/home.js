// This page is only for signed-in users.
Auth.currentUser().then((user) => {
  if (!user) {
    window.location.replace('index.html');
    return;
  }
  document.getElementById('user-email').textContent = user.email;
}).catch(() => window.location.replace('index.html'));

document.getElementById('sign-out').addEventListener('click', async () => {
  await Auth.signOut().catch(() => {});
  window.location.assign('index.html');
});

// Placeholder until the schedule builder and saved-schedule pages exist.
const note = document.getElementById('choice-note');
const labels = {
  new: 'The schedule builder is coming soon.',
  existing: 'Saved schedules are coming soon.',
};

document.querySelectorAll('.choice').forEach((button) => {
  button.addEventListener('click', () => {
    note.textContent = labels[button.dataset.choice];
    note.hidden = false;
  });
});
