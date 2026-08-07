(function () {
  if (!/(^|\.)youtube\.com$/.test(location.hostname)) return;
  try {
    var request = new XMLHttpRequest();
    request.open('GET', 'https://cdn.jsdelivr.net/gh/YossiMH/TizenTubeCobalt@main/x.js', false);
    request.send();
    if (request.status === 0 || request.status < 400) (0, eval)(request.responseText);
  } catch (error) {
    console.error('TizenTube Allowed-Only loader failed', error);
  }
})();
