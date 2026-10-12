// Shared by every signed-in page: sends visitors who are not signed in back
// to the sign-in page, shows the user's email, and wires the Sign out button.

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
