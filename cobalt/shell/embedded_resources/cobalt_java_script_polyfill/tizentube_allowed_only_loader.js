(function () {
  if (!/(^\.|^)youtube\.com$/.test(location.hostname)) return;
  try {
    var s = document.createElement('script');
    s.src = 'https://cdn.jsdelivr.net/gh/YossiMH/TizenTubeCobalt@main/x.js';
    (document.head || document.documentElement).appendChild(s);
  } catch (error) {
    console.error('TizenTube Allowed-Only loader failed', error);
  }
})();
