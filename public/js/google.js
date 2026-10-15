// "Continue with Google" on the sign in and sign up pages.
//
// The button is a plain link to /api/google/start, which sends the browser to
// Google. It stays hidden unless the server has Google sign-in configured.
// If sign-in fails, the server sends the browser back here with ?google=<reason>.

(() => {
  const button = document.getElementById('google-btn');
  const divider = document.getElementById('google-divider');
  const remember = document.getElementById('remember');
  const formError = document.getElementById('form-error');

  const MESSAGES = {
    cancelled: 'Google sign-in was cancelled.',
    unverified: 'Your Google account’s email address is not verified. Verify it with Google, then try again.',
    expired: 'Google sign-in took too long or was started in another tab. Please try again.',
    unavailable: 'Google sign-in is not set up on this server.',
    failed: 'Google sign-in did not work. Please try again.',
  };

  const reason = new URLSearchParams(window.location.search).get('google');
  if (reason) {
    formError.textContent = MESSAGES[reason] || MESSAGES.failed;
    formError.hidden = false;
    // Drop ?google= so a refresh does not show the message again.
    history.replaceState(null, '', window.location.pathname);
  }

  Auth.request('GET', '/api/auth-options').then((options) => {
    if (!options.google) return;
    button.hidden = false;
    divider.hidden = false;
  }).catch(() => {});

  button.addEventListener('click', (event) => {
    event.preventDefault();
    const keepSignedIn = remember !== null && remember.checked;
    window.location.assign('/api/google/start' + (keepSignedIn ? '?remember=true' : ''));
  });
})();
