// Form helpers shared by the sign-in, sign-up and password reset pages.

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MIN_PASSWORD_LENGTH = 8;

function setFieldError(input, message) {
  const errorEl = document.getElementById(`${input.id}-error`);
  errorEl.textContent = message;
  errorEl.hidden = !message;
  input.setAttribute('aria-invalid', message ? 'true' : 'false');
}

function validateEmail(input) {
  const value = input.value.trim();
  let message = '';
  if (!value) message = 'Enter your email address.';
  else if (!EMAIL_PATTERN.test(value)) message = 'Enter a valid email address, like you@university.edu.';
  setFieldError(input, message);
  return !message;
}

function validatePassword(input) {
  const value = input.value;
  let message = '';
  if (!value) message = 'Enter your password.';
  else if (value.length < MIN_PASSWORD_LENGTH) message = `Password must be at least ${MIN_PASSWORD_LENGTH} characters.`;
  setFieldError(input, message);
  return !message;
}

function validateConfirmation(input, passwordInput) {
  const message = input.value === passwordInput.value ? '' : 'Passwords do not match.';
  setFieldError(input, message);
  return !message;
}

// Clear an error as soon as the user starts fixing the field.
function clearErrorsOnInput(...inputs) {
  inputs.forEach((input) => input.addEventListener('input', () => setFieldError(input, '')));
}

function wirePasswordToggle(button, input) {
  button.addEventListener('click', () => {
    const showing = input.type === 'text';
    input.type = showing ? 'password' : 'text';
    button.textContent = showing ? 'Show' : 'Hide';
    button.setAttribute('aria-pressed', String(!showing));
  });
}

// Runs action() with the button disabled, showing any failure in errorEl.
// Returns true on success; on failure the button is restored for another try.
async function submitWith(button, busyLabel, errorEl, action) {
  const idleLabel = button.textContent;
  errorEl.hidden = true;
  button.disabled = true;
  button.textContent = busyLabel;
  try {
    await action();
    return true;
  } catch (err) {
    errorEl.textContent = err.message || 'Something went wrong. Please try again.';
    errorEl.hidden = false;
    button.disabled = false;
    button.textContent = idleLabel;
    return false;
  }
}
