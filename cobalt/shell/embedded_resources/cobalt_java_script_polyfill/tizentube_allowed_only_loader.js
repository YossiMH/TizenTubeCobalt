// Trusted-Types-safe document-start loader (readable reference; the APK
// patcher generates the exact pinned-URL variant that ships inside the app).
// YouTube TV enforces Trusted Types: plain script.src assignment is refused
// ("This document requires 'TrustedScriptURL' assignment"), so the loader
// wraps the URL in a TrustedScriptURL produced by a policy.
(function () {
  if (!/(^|\.)youtube\.com$/.test(location.hostname)) return;
  try {
    var u = 'https://cdn.jsdelivr.net/gh/YossiMH/TizenTubeCobalt@main/x.js';
    var s = document.createElement('script');
    s.src = trustedTypes
      ? trustedTypes.createPolicy('t', { createScriptURL: (x) => x }).createScriptURL(u)
      : u;
    document.documentElement.appendChild(s);
  } catch (error) {
    console.error('TizenTube Allowed-Only loader failed', error);
  }
})();
