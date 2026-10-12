const form = document.getElementById('signup-form');
const emailInput = document.getElementById('email');
const passwordInput = document.getElementById('password');
const confirmInput = document.getElementById('confirm');
const submitBtn = document.getElementById('submit-btn');
const formError = document.getElementById('form-error');

clearErrorsOnInput(emailInput, passwordInput, confirmInput);
wirePasswordToggle(document.getElementById('toggle-password'), passwordInput);

form.addEventListener('submit', async (event) => {
  event.preventDefault();

  const emailOk = validateEmail(emailInput);
  const passwordOk = validatePassword(passwordInput);
  const confirmOk = validateConfirmation(confirmInput, passwordInput);
  if (!emailOk || !passwordOk || !confirmOk) {
    (!emailOk ? emailInput : !passwordOk ? passwordInput : confirmInput).focus();
    return;
  }

  // The server signs the new account in, so go straight to the home page.
  const ok = await submitWith(submitBtn, 'Creating account…', formError, () =>
    Auth.signUp(emailInput.value.trim(), passwordInput.value));
  if (ok) window.location.assign('home.html');
});
