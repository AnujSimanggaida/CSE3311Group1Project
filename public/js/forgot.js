const form = document.getElementById('forgot-form');
const emailInput = document.getElementById('email');
const submitBtn = document.getElementById('submit-btn');
const formError = document.getElementById('form-error');
const sent = document.getElementById('sent');

clearErrorsOnInput(emailInput);

form.addEventListener('submit', async (event) => {
  event.preventDefault();

  if (!validateEmail(emailInput)) {
    emailInput.focus();
    return;
  }

  const ok = await submitWith(submitBtn, 'Sending…', formError, () =>
    Auth.requestPasswordReset(emailInput.value.trim()));
  if (ok) {
    form.hidden = true;
    sent.hidden = false;
  }
});
