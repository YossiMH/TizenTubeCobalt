const assert=require('assert'),fs=require('fs'),path=require('path');
const s=fs.readFileSync(path.join(__dirname,'onn_setup.ps1'),'utf8');
assert.ok(s.includes("'dev.dworks.apps.anexplorer'"),'AnExplorer browser bypass must be locked');
assert.ok(s.includes("'com.phlox.tvwebbrowser'"));
assert.ok(s.includes("'com.internet.tvwebbrowser'"));
assert.ok(s.includes("'com.liskovsoft.smarttubetv.beta'"));
assert.ok(s.includes("$ForkId = 'io.gh.yossim.tizentube.cobalt'"));
console.log('All TizenTube Onn lockdown tests passed.');
