// Already signed in? Skip the form.
Auth.currentUser().then((user) => {
  if (user) window.location.replace('home.html');
}).catch(() => {});

const form = document.getElementById('login-form');
const emailInput = document.getElementById('email');
const passwordInput = document.getElementById('password');
const rememberInput = document.getElementById('remember');
const submitBtn = document.getElementById('submit-btn');
const formError = document.getElementById('form-error');

clearErrorsOnInput(emailInput, passwordInput);
wirePasswordToggle(document.getElementById('toggle-password'), passwordInput);

form.addEventListener('submit', async (event) => {
  event.preventDefault();

  const emailOk = validateEmail(emailInput);
  const passwordOk = validatePassword(passwordInput);
  if (!emailOk || !passwordOk) {
    (emailOk ? passwordInput : emailInput).focus();
    return;
  }

  const ok = await submitWith(submitBtn, 'Signing in…', formError, () =>
    Auth.signIn(emailInput.value.trim(), passwordInput.value, rememberInput.checked));
  if (ok) window.location.assign('home.html');
});
