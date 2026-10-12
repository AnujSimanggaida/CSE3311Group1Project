// Client for the CourseSweep account API.
//
// The session lives in an HttpOnly cookie set by the server, so page scripts
// never see or store the session token or the password.

const Auth = (() => {
  async function request(method, path, body) {
    const options = { method, credentials: 'same-origin' };
    if (body !== undefined) {
      options.headers = { 'Content-Type': 'application/json' };
      options.body = JSON.stringify(body);
    }

    let response;
    try {
      response = await fetch(path, options);
    } catch {
      throw new Error('Could not reach the server. Check your connection and try again.');
    }

    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      const error = new Error(data.error || 'Something went wrong. Please try again.');
      error.status = response.status;
      throw error;
    }
    return data;
  }

  function signIn(email, password, remember) {
    return request('POST', '/api/login', { email, password, remember });
  }

  function signUp(email, password) {
    return request('POST', '/api/signup', { email, password });
  }

  function signOut() {
    return request('POST', '/api/logout', {});
  }

  // Resolves to { email } when signed in, or null when not.
  async function currentUser() {
    try {
      return await request('GET', '/api/me');
    } catch (err) {
      if (err.status === 401) return null;
      throw err;
    }
  }

  function requestPasswordReset(email) {
    return request('POST', '/api/forgot-password', { email });
  }

  function resetPassword(token, password) {
    return request('POST', '/api/reset-password', { token, password });
  }

  return { signIn, signUp, signOut, currentUser, requestPasswordReset, resetPassword };
})();
