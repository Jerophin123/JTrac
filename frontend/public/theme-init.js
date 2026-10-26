// Resolves the user's theme preference before the app loads to avoid a flash of the
// wrong theme. The preference is a UI convenience only; it is safe if storage is unavailable.
(function () {
  var preference = 'system';
  try {
    preference = window.localStorage.getItem('jtrac.theme') || window.localStorage.getItem('jtac.theme') || 'system';
  } catch {
    /* storage unavailable: fall back to the system setting */
  }
  var dark =
    preference === 'dark' ||
    (preference !== 'light' && window.matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
})();
