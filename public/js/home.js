document.querySelector('[data-choice="new"]').addEventListener('click', () => {
  window.location.assign('schedule.html');
});

document.querySelector('[data-choice="existing"]').addEventListener('click', () => {
  window.location.assign('schedules.html');
});
