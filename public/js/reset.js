const form = document.getElementById('reset-form');
const passwordInput = document.getElementById('password');
const confirmInput = document.getElementById('confirm');
const submitBtn = document.getElementById('submit-btn');
const formError = document.getElementById('form-error');
const done = document.getElementById('done');

// The token arrives in the URL fragment (reset.html#token=...). Take it out of
// the address bar straight away so it is not left in the browser history.
const token = new URLSearchParams(window.location.hash.slice(1)).get('token');
history.replaceState(null, '', window.location.pathname);

if (!token) {
  form.hidden = true;
  formError.textContent = 'This reset link is invalid. Request a new one below.';
  formError.hidden = false;
}

clearErrorsOnInput(passwordInput, confirmInput);
wirePasswordToggle(document.getElementById('toggle-password'), passwordInput);

form.addEventListener('submit', async (event) => {
  event.preventDefault();

  const passwordOk = validatePassword(passwordInput);
  const confirmOk = validateConfirmation(confirmInput, passwordInput);
  if (!passwordOk || !confirmOk) {
    (passwordOk ? confirmInput : passwordInput).focus();
    return;
  }

  const ok = await submitWith(submitBtn, 'Saving…', formError, () =>
    Auth.resetPassword(token, passwordInput.value));
  if (ok) {
    form.hidden = true;
    done.hidden = false;
  }
});
