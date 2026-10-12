const note = document.getElementById('choice-note');

document.querySelector('[data-choice="new"]').addEventListener('click', () => {
  window.location.assign('schedule.html');
});

// Placeholder until the saved-schedule page exists.
document.querySelector('[data-choice="existing"]').addEventListener('click', () => {
  note.textContent = 'Saved schedules are coming soon.';
  note.hidden = false;
});
