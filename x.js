(function(g,f){var a=f(g||{});if(typeof module!='undefined'&&module.exports)module.exports=a;if(g&&g.document&&g.location)a.install()})(typeof window!='undefined'?window:globalThis,function(w){'use strict';
if(w.__ttAllowedOnly)return w.__ttAllowedOnly;
var S={liked:new Set,subs:new Set,playlist:new Set,savedPlaylists:new Set,savedPlaylistContinuations:new Set,savedLibraryContinuations:new Set,music:new Set,purchased:new Set,accountVideos:new Set,v2c:new Map,ctx:null,key:null,hdr:{},ready:false,p:null,fetch0:null,installed:false,errors:[],retries:0,loggedIn:null,lastLoggedIn:null,stripped:0,booting:false,mediaGate:'pending',statusEl:null,statusWatcher:null,xhrArmed:false,arm:null,armFail:0,allowedNames:new Set,morningProgress:null,morningPlaying:null};
var R={busy:false,pending:null,hash:null,installed:false};
var REASON='Only videos you liked or videos from channels you subscribe to are available.';
function statusText(){if(S.booting)return'Restricted YouTube - Loading your liked videos and subscriptions...';if(S.loggedIn===true&&(S.errors.length||!S.ready))return'Restricted YouTube - Protecting you: list unavailable, retrying';if(S.ready&&S.loggedIn===true)return'Restricted YouTube - Ready: '+S.liked.size+' liked, '+S.subs.size+' subscribed';if(S.loggedIn===false&&S.ready)return'Restricted YouTube - Signed out: all videos blocked';if(!S.ready)return'Restricted YouTube - Protecting you: list unavailable, retrying';return'Restricted YouTube - Starting...'}
function addPageStyle(d,id,css){if(!d||!d.createElement)return null;try{if(d.getElementById){var old=d.getElementById(id);if(old)return old}}catch(e){}var st=d.createElement('style');st.id=id;if(st.setAttribute)st.setAttribute('id',id);try{var q=d.querySelector&&d.querySelector('style[nonce],script[nonce]'),n=q&&(q.nonce||(q.getAttribute&&q.getAttribute('nonce')));if(n&&st.setAttribute)st.setAttribute('nonce',n)}catch(e){}st.textContent=css;var r=d.head||d.body||d.documentElement;if(r&&r.appendChild)try{r.appendChild(st)}catch(e){}return st}
function renderStatus(){var t=statusText(),d=w.document;if(!d||!d.createElement)return t;var e=S.statusEl;if(!e){addPageStyle(d,'tt-allowed-only-style','#tt-allowed-only-status{position:fixed;top:12px;right:12px;z-index:2147483647;padding:8px 12px;border-radius:8px;background:rgba(0,0,0,.82);color:#fff;font:600 18px/1.25 Arial,sans-serif;pointer-events:none;max-width:52vw;white-space:normal;text-align:left;box-shadow:0 2px 8px rgba(0,0,0,.45)}');e=d.createElement('div');S.statusEl=e;e.id='tt-allowed-only-status';if(e.setAttribute){e.setAttribute('id','tt-allowed-only-status');e.setAttribute('role','status')}e.textContent=t;var r=d.body||d.documentElement;if(r&&e.parentNode!==r&&r.appendChild)try{r.appendChild(e)}catch(x){}}else if(e.textContent!==t)e.textContent=t;return t}
function statusTick(){try{cfg();guard();if(!S.ready&&S.ctx)ensure();renderStatus();pumpMorningHistoryRefresh();pumpMorningStatus()}catch(e){}}function watchStatus(){if(S.statusWatcher||typeof w.setInterval!='function')return;S.statusWatcher=w.setInterval(statusTick,1500)}
function obj(x){return x&&typeof x=='object'}
function walk(x,fn,path){path=path||[];if(!obj(x))return;fn(x,path);if(Array.isArray(x)){for(var i=0;i<x.length;i++)walk(x[i],fn,path.concat(i))}else Object.keys(x).forEach(function(k){walk(x[k],fn,path.concat(k))})}
function uc(x){return typeof x=='string'&&/^UC[\w-]+$/.test(x)?x:null}
function tcid(x){var t=x&&x.tileRenderer;if(t){var a=[t.contentId,t.onSelectCommand&&t.onSelectCommand.browseEndpoint&&t.onSelectCommand.browseEndpoint.browseId];for(var i=0;i<a.length;i++){var c=uc(a[i]);if(c)return c}}return null}
function cid(x){var r=null;walk(x,function(o){if(r)return;var a=[o.channelId,o.externalChannelId,o.browseId,o.browseEndpoint&&o.browseEndpoint.browseId,o.navigationEndpoint&&o.navigationEndpoint.browseEndpoint&&o.navigationEndpoint.browseEndpoint.browseId];for(var i=0;i<a.length;i++){var c=uc(a[i]);if(c){r=c;break}}});return r}
function scopedCid(x){if(!obj(x))return null;var r=null;walk(x,function(o){if(r)return;var a=[o.channelId,o.externalChannelId,o.browseId,o.browseEndpoint&&o.browseEndpoint.browseId,o.navigationEndpoint&&o.navigationEndpoint.browseEndpoint&&o.navigationEndpoint.browseEndpoint.browseId];for(var i=0;i<a.length;i++){var c=uc(a[i]);if(c){r=c;break}}});return r}
function tileMenuCid(t){try{var a=t&&t.onLongPressCommand&&t.onLongPressCommand.showMenuCommand&&t.onLongPressCommand.showMenuCommand.menu&&t.onLongPressCommand.showMenuCommand.menu.menuRenderer&&t.onLongPressCommand.showMenuCommand.menu.menuRenderer.items;if(!Array.isArray(a))return null;for(var i=0;i<a.length;i++){var r=a[i]&&a[i].menuNavigationItemRenderer,c=uc(r&&r.navigationEndpoint&&r.navigationEndpoint.browseEndpoint&&r.navigationEndpoint.browseEndpoint.browseId);if(c)return c}}catch(e){}return null}
function vcid(x){if(!obj(x))return null;var t=x.tileRenderer||x,d=t.videoDetails||x.videoDetails||null,a=[d&&d.channelId,d&&d.externalChannelId,t.channelId,t.externalChannelId];for(var i=0;i<a.length;i++){var c=uc(a[i]);if(c)return c}var mc=tileMenuCid(t);if(mc)return mc;var scopes=[t.ownerText,t.shortBylineText,t.longBylineText,t.byline,t.channelName];for(var j=0;j<scopes.length;j++){var c2=scopedCid(scopes[j]);if(c2)return c2}return null}
function authcid(x){return vid(x)?vcid(x):(tcid(x)||vcid(x))}
function tvid(x){var t=x&&x.tileRenderer;if(!t&&x&&x.contentType&&x.onSelectCommand)t=x;if(!t)return null;var e=t.onSelectCommand&&t.onSelectCommand.watchEndpoint&&t.onSelectCommand.watchEndpoint.videoId;if(typeof e=='string')return e;return t.contentType=='TILE_CONTENT_TYPE_VIDEO'&&typeof t.contentId=='string'?t.contentId:null}function vid(x){if(!obj(x))return null;var t=tvid(x);if(t)return t;return typeof x.videoId=='string'?x.videoId:x.videoDetails&&x.videoDetails.videoId||x.watchEndpoint&&x.watchEndpoint.videoId||x.navigationEndpoint&&x.navigationEndpoint.watchEndpoint&&x.navigationEndpoint.watchEndpoint.videoId||x.innertubeCommand&&x.innertubeCommand.watchEndpoint&&x.innertubeCommand.watchEndpoint.videoId||null}
function vnode(x){return !!(obj(x)&&((x.tileRenderer&&(tvid(x)||tcid(x)))||(typeof x.videoId=='string'||x.videoDetails&&x.videoDetails.videoId||x.watchEndpoint&&x.watchEndpoint.videoId||x.navigationEndpoint&&x.navigationEndpoint.watchEndpoint&&x.navigationEndpoint.watchEndpoint.videoId)))}
function remember(x){var v=vid(x),c=v&&vcid(x);if(v&&c)S.v2c.set(v,c)}
function ok(v,c){if(!v)return !!c&&S.subs.has(c);if(S.loggedIn===false||!S.ready)return false;if(S.liked.has(v)||S.playlist.has(v)||S.music.has(v)||S.purchased.has(v))return true;c=c||S.v2c.get(v);return !!c&&S.subs.has(c)}
function emptyFilteredShelf(x){var sh=x&&x.shelfRenderer,c=sh&&sh.content;if(!obj(c))return false;var rs=[c.horizontalListRenderer,c.gridRenderer,c.expandedShelfContentsRenderer];for(var i=0;i<rs.length;i++){var r=rs[i];if(!obj(r))continue;var a=Array.isArray(r.items)?r.items:Array.isArray(r.contents)?r.contents:null;if(a&&a.length===0)return true}return false}
function filter(x){if(Array.isArray(x)){var a=[];x.forEach(function(y){if(vnode(y)){remember(y);if(!ok(vid(y),authcid(y))){S.stripped++;return}}y=filter(y);if(y!=null)a.push(y)});return a}if(!obj(x))return x;if(vnode(x)){remember(x);if(!ok(vid(x),authcid(x))){S.stripped++;return null}}var ks=Object.keys(x);ks.forEach(function(k){var y=filter(x[k]);if(y==null)delete x[k];else x[k]=y});if(emptyFilteredShelf(x))return null;return ks.length&&!Object.keys(x).length?null:x}
function player(x){if(!obj(x))return x;var d=x.videoDetails||{},v=d.videoId||vid(x),c=uc(d.channelId)||uc(d.externalChannelId);if(v&&c)S.v2c.set(v,c);if(!v||ok(v,c))return x;S.stripped++;delete x.streamingData;delete x.captions;delete x.storyboards;x.playabilityStatus={status:'ERROR',reason:REASON,errorScreen:{playerErrorMessageRenderer:{reason:{simpleText:REASON}}}};return x}
function liked(x,t){t=t||S.liked;walk(x,function(o,p){var k=p.length?String(p[p.length-1]):'',scoped=false;for(var i=0;i<p.length;i++)if(/^(?:playlistVideoListRenderer|playlistPanelRenderer|likedVideosRenderer|playlistRenderer)$/i.test(String(p[i]))){scoped=true;break}var explicit=k==='playlistVideoRenderer'||k==='playlistPanelVideoRenderer',modern=scoped&&(k==='videoRenderer'||k==='gridVideoRenderer'||k==='tileRenderer'&&o.contentType==='TILE_CONTENT_TYPE_VIDEO');if(explicit||modern){var v=vid(o);if(v)t.add(v)}});return t}
function channelName(o){try{var q=o&&o.metadata&&o.metadata.tileMetadataRenderer&&o.metadata.tileMetadataRenderer.title||o&&o.title||o&&o.onLongPressCommand&&o.onLongPressCommand.showMenuCommand&&o.onLongPressCommand.showMenuCommand.title;if(q&&typeof q.simpleText==='string')return q.simpleText.trim();if(q&&Array.isArray(q.runs))return q.runs.map(function(r){return r&&r.text||''}).join('').trim()}catch(e){}return''}
function channels(x,t){t=t||S.subs;walk(x,function(o,p){var k=p.length?String(p[p.length-1]):'',isTile=k=='tileRenderer'&&o.contentType=='TILE_CONTENT_TYPE_CHANNEL';if(!isTile&&!/^(?:channelRenderer|gridChannelRenderer|compactChannelRenderer|channelListItemRenderer)$/.test(k))return;var a=[o.channelId,o.externalChannelId,o.browseId,o.browseEndpoint&&o.browseEndpoint.browseId,o.navigationEndpoint&&o.navigationEndpoint.browseEndpoint&&o.navigationEndpoint.browseEndpoint.browseId,o.onSelectCommand&&o.onSelectCommand.browseEndpoint&&o.onSelectCommand.browseEndpoint.browseId];for(var i=0;i<a.length;i++){var c=uc(a[i]);if(c){t.add(c);var n=channelName(o);if(n)S.allowedNames.add(n.toLowerCase());break}}});return t}
function guide(x,t){t=t||S.subs;(function r(n,inside){if(!obj(n))return;if(Array.isArray(n)){n.forEach(function(v){r(v,inside)});return}Object.keys(n).forEach(function(k){var y=n[k],z=inside||/^(?:guideSubscriptionsSectionRenderer|subscriptionsSectionRenderer)$/.test(k);if(z&&obj(y)){var c=uc(y.browseId)||uc(y.channelId)||uc(y.externalChannelId)||uc(y.browseEndpoint&&y.browseEndpoint.browseId)||uc(y.navigationEndpoint&&y.navigationEndpoint.browseEndpoint&&y.navigationEndpoint.browseEndpoint.browseId);if(c)t.add(c)}r(y,z)})})(x,false);return t}
function responseChannelId(x){if(!obj(x))return null;var h=null,r=null;try{var b=x.contents&&x.contents.tvBrowseRenderer&&x.contents.tvBrowseRenderer.content&&x.contents.tvBrowseRenderer.content.tvSurfaceContentRenderer&&x.contents.tvBrowseRenderer.content.tvSurfaceContentRenderer.header&&x.contents.tvBrowseRenderer.content.tvSurfaceContentRenderer.header.channelHeaderRenderer&&x.contents.tvBrowseRenderer.content.tvSurfaceContentRenderer.header.channelHeaderRenderer.buttons||[];for(var i=0;i<b.length;i++){var q=b[i]&&b[i].subscribeButtonRenderer,c=uc(q&&q.channelId);if(c){h=c;break}}}catch(e){}try{var a=x.responseContext&&x.responseContext.serviceTrackingParams||[];for(var j=0;j<a.length;j++){var p=a[j]&&a[j].params||[];for(var k=0;k<p.length;k++)if(p[k]&&p[k].key==='browse_id'){var c2=uc(p[k].value);if(c2){r=c2;break}}if(r)break}}catch(e){}return h&&r&&h===r?h:null}
function shelfTitle(sh){try{var h=sh&&sh.headerRenderer&&sh.headerRenderer.shelfHeaderRenderer,t=h&&(h.title||h.avatarLockup&&h.avatarLockup.avatarLockupRenderer&&h.avatarLockup.avatarLockupRenderer.title);if(t&&typeof t.simpleText==='string')return t.simpleText;if(t&&Array.isArray(t.runs))return t.runs.map(function(v){return v&&v.text||''}).join('')}catch(e){}return''}
function rememberChannelPageVideos(x){var c=responseChannelId(x);if(!c||!S.subs.has(c))return 0;var a=null;try{a=x.contents.tvBrowseRenderer.content.tvSurfaceContentRenderer.content.sectionListRenderer.contents}catch(e){}if(!Array.isArray(a))return 0;var n=0;for(var i=0;i<a.length;i++){var sh=a[i]&&a[i].shelfRenderer;if(!sh||shelfTitle(sh)!=='Videos')continue;var ep=sh.endpoint&&sh.endpoint.browseEndpoint;if(!ep){try{var rr=sh.headerRenderer.shelfHeaderRenderer.title.runs||[];for(var z=0;z<rr.length;z++){var e2=rr[z]&&rr[z].navigationEndpoint&&rr[z].navigationEndpoint.browseEndpoint;if(e2){ep=e2;break}}}catch(e){}}if(!ep||uc(ep.browseId)!==c||typeof ep.params!=='string'||!ep.params)continue;var items=sh.content&&sh.content.horizontalListRenderer&&sh.content.horizontalListRenderer.items||[];for(var q=0;q<items.length;q++){var v=tvid(items[q]);if(v){S.v2c.set(v,c);n++}}}return n}
function trusted(x){if(!obj(x))return false;var s=x.status;if(typeof s=='string'&&!/^(?:ok|success|complete|loaded)$/i.test(s))return false;if(x.error||x.errorScreen)return false;return !(x.playabilityStatus&&x.playabilityStatus.status&&x.playabilityStatus.status!='OK')}
function membership(t,v){if(typeof v=='string'&&v.length){t.add(v);S.accountVideos.add(v)}}
function textOf(x){var a=[];walk(x,function(o){if(typeof o.simpleText=='string')a.push(o.simpleText);if(typeof o.label=='string')a.push(o.label);if(Array.isArray(o.runs))o.runs.forEach(function(r){if(r&&typeof r.text=='string')a.push(r.text)})});return a.join(' ')}
function normalizePlaylistBrowseId(p){if(typeof p!='string'||!p)return null;if(/^VL/.test(p))return p;if(/^(?:PL|FL|RD)/.test(p))return'VL'+p;return null}
function playlistBrowseId(o){if(!obj(o))return null;var p=o.playlistId||o.navigationEndpoint&&o.navigationEndpoint.browseEndpoint&&o.navigationEndpoint.browseEndpoint.browseId||o.contentId||o.onSelectCommand&&o.onSelectCommand.browseEndpoint&&o.onSelectCommand.browseEndpoint.browseId;return normalizePlaylistBrowseId(p)}
function collectPlaylistIds(x,t){t=t||new Set;if(!trusted(x))return t;walk(x,function(o,p){var k=p.length?String(p[p.length-1]):'';if(k=='playlistRenderer'||k=='gridPlaylistRenderer'||k=='playlistCardRenderer'||k=='tileRenderer'&&o.contentType=='TILE_CONTENT_TYPE_PLAYLIST'){var b=playlistBrowseId(o);if(b)t.add(b)}});return t}
function collectPlaylistVideoIds(x,t){t=t||S.playlist;if(!trusted(x))return t;walk(x,function(o,p){var k=p.length?String(p[p.length-1]):'',scoped=false;for(var i=0;i<p.length;i++)if(/^(?:playlistVideoListRenderer|playlistPanelRenderer|playlistRenderer)$/i.test(String(p[i]))){scoped=true;break}var explicit=k=='playlistVideoRenderer'||k=='playlistPanelVideoRenderer'||k=='tileRenderer'&&o.contentType=='TILE_CONTENT_TYPE_VIDEO',modern=scoped&&/^(?:videoRenderer|gridVideoRenderer|compactVideoRenderer)$/.test(k);if(explicit||modern){var v=vid(o);if(v)membership(t,v)}});return t}
function rememberContinuations(x,t){cont(x).forEach(function(c){t.add(c)});return t}
function learnAccountPlaylistResponse(x,b){if(!obj(b)||!trusted(x))return false;var token=typeof b.continuation=='string'?b.continuation:null,isLibrary=b.browseId=='FElibrary'&&b.params=='FEplaylist_aggregation'||token&&S.savedLibraryContinuations.has(token);if(isLibrary){collectPlaylistIds(x,S.savedPlaylists);rememberContinuations(x,S.savedLibraryContinuations);return true}var id=normalizePlaylistBrowseId(b.browseId),isSaved=id&&S.savedPlaylists.has(id)||token&&S.savedPlaylistContinuations.has(token);if(!isSaved)return false;collectPlaylistVideoIds(x,S.playlist);rememberContinuations(x,S.savedPlaylistContinuations);return true}
function musicSourceTrusted(s,k){if(!obj(s)||s.ownership!='account'||typeof s.endpoint!='string'||!(/^(?:FElibrary|FEmusic_[\w-]+|VL[\w-]+)$/.test(s.endpoint)))return false;if(s.provenance=='account-library')return k=='library';if(s.provenance=='account-collection')return s.collection=='artist-album'&&(k=='collection'||k=='library');return false}
function collectMusicLibraryVideoIds(x,t,s){t=t||S.music;if(!trusted(x)||!musicSourceTrusted(s,'library'))return t;walk(x,function(o,p){var k=p.length?String(p[p.length-1]):'',m=k=='musicResponsiveListItemRenderer'?o:null;if(m&&m.playlistItemData&&typeof m.playlistItemData.videoId=='string')membership(t,m.playlistItemData.videoId)});return t}
function collectMusicCollectionVideoIds(x,t,s){t=t||S.music;var a=Array.isArray(x)?x:[x];a.forEach(function(y){if(trusted(y)&&musicSourceTrusted(s,'collection'))collectMusicLibraryVideoIds(y,t,s)});return t}
function collectMusicCollectionBrowseIds(x,t,s){t=t||new Set;if(!trusted(x)||!musicSourceTrusted(s,'collection'))return t;walk(x,function(o,p){var k=p.length?String(p[p.length-1]):'';if(!/(?:musicTwoRowItemRenderer|musicResponsiveListItemRenderer|musicNavigationButtonRenderer)$/.test(k))return;var b=o.navigationEndpoint&&o.navigationEndpoint.browseEndpoint&&o.navigationEndpoint.browseEndpoint.browseId||o.onTap&&o.onTap.browseEndpoint&&o.onTap.browseEndpoint.browseId;if(typeof b=='string'&&b)t.add(b)});return t}
function purchasedMarker(r){if(!r)return false;var labels=[];if(Array.isArray(r.badges))r.badges.forEach(function(b){labels.push(textOf(b))});if(typeof r.purchaseState=='string')labels.push(r.purchaseState);if(typeof r.ownershipStatus=='string')labels.push(r.ownershipStatus);if(typeof r.purchaseDetails=='string')labels.push(r.purchaseDetails);return labels.some(function(v){return /^(?:purchased|owned|purchased content)$/i.test(String(v).trim())})}
function purchaseSourceSafe(x){var marker='safe';function scan(n){if(marker!='safe'||!obj(n))return;Object.keys(n).forEach(function(k){if(/(?:search|filter|preview)/i.test(k))marker='unsafe';else scan(n[k])})}scan(x);if(marker!='safe')return false;walk(x,function(o,p){var k=p.length?String(p[p.length-1]).toLowerCase():'';if((/menu/i.test(k)&&/preview/i.test(textOf(o)))||/preview/i.test(textOf(o))&&/menu/i.test(k))marker='unsafe'});return marker=='safe'}
function collectPurchasedVideoIds(x,t){t=t||S.purchased;if(!trusted(x)||!purchaseSourceSafe(x))return t;walk(x,function(o,p){var k=p.length?String(p[p.length-1]):'';if(k=='videoRenderer'&&purchasedMarker(o)){var v=vid(o);if(v)membership(t,v)}});return t}
function collectChannelNames(x){walk(x,function(o,p){var name=null;if(o.title){if(o.title.runs&&o.title.runs.length&&typeof o.title.runs[0].text=='string')name=o.title.runs[0].text;else if(typeof o.title.simpleText=='string')name=o.title.simpleText;}if(!name&&o.ownerText&&o.ownerText.runs&&o.ownerText.runs.length&&typeof o.ownerText.runs[0].text=='string')name=o.ownerText.runs[0].text;var hasChan=o.channelId||o.externalChannelId||o.browseId||(o.browseEndpoint&&o.browseEndpoint.browseId)||(o.navigationEndpoint&&o.navigationEndpoint.browseEndpoint&&o.navigationEndpoint.browseEndpoint.browseId);if(name&&hasChan)S.allowedNames.add(name.toLowerCase())});return S.allowedNames}
function tileShouldStay(text,names){if(!text)return false;var lines=String(text).split(/\r?\n/).map(function(v){return v.trim().toLowerCase()}).filter(Boolean);for(var i=0;i<lines.length;i++)if(names.has(lines[i]))return true;return false}
function sweepDom(){if(!S.allowedNames.size&&(!S.ready||S.subs.size>0))return 0;if(typeof document=='undefined'||!document.querySelectorAll)return 0;var removed=0;var tiles=document.querySelectorAll('ytlr-tile-renderer');for(var i=0;i<tiles.length;i++){var t=tiles[i];if(t.getAttribute('role')==='button')continue;var v=null;try{if(typeof w.__ttElementVideoId=='function')v=w.__ttElementVideoId(t)}catch(e){}if(v&&ok(v))continue;var txt=t.innerText||t.textContent||'';if(v||!tileShouldStay(txt,S.allowedNames)){if(t.parentNode)t.parentNode.removeChild(t);removed++}}S.stripped+=removed;return removed}
function cont(x){var a=[];function add(c){if(typeof c=='string'&&c&&a.indexOf(c)<0)a.push(c)}walk(x,function(o,p){var k=p.length?String(p[p.length-1]):'';if(k=='continuationItemRenderer'){add(o.continuationEndpoint&&o.continuationEndpoint.continuationCommand&&o.continuationEndpoint.continuationCommand.token);add(o.continuationEndpoint&&o.continuationEndpoint.nextContinuationData&&o.continuationEndpoint.nextContinuationData.continuation)}if(o.nextContinuationData)add(o.nextContinuationData.continuation)});return a}
function json(s){try{return typeof s=='string'&&s?JSON.parse(s):null}catch(e){return null}}
function authed(){try{return Object.keys(S.hdr).some(function(k){return k.toLowerCase()=='authorization'&&!!S.hdr[k]})||(S.ctx&&S.ctx.user&&(S.ctx.user.onBehalfOfUser||S.ctx.user.delegatedThirdPartyIdentity||S.ctx.user.authenticatedUser))}catch(e){return false}}
function cfg(){try{var y=w.ytcfg;if(!y)return;var get=typeof y.get=='function'?function(k){return y.get(k)}:function(k){return y.data_&&y.data_[k]};S.ctx=S.ctx||get('INNERTUBE_CONTEXT');S.key=S.key||get('INNERTUBE_API_KEY');var li=get('LOGGED_IN'),signed=authed();if(signed)S.loggedIn=true;else if(li===true)S.loggedIn=true;else if(li===false)S.loggedIn=false;if(S.loggedIn!==null&&S.loggedIn!==S.lastLoggedIn){S.lastLoggedIn=S.loggedIn;S.ready=false;S.p=null;S.liked.clear();S.subs.clear();S.playlist.clear();S.savedPlaylists.clear();S.savedPlaylistContinuations.clear();S.savedLibraryContinuations.clear();S.music.clear();S.purchased.clear();S.accountVideos.clear();S.v2c.clear();S.allowedNames.clear();S.morningProgress=null;S.morningProgressAt=0;S.morningHistoryStatus=null;S.morningHistoryAccountIdentity=null;S.morningStatusUntilElapsed=0;S.errors=[];S.retries=0;S.stripped=0;S.booting=false;S.mediaGate='pending';S.ctx=null;S.key=null;renderStatus()}}catch(e){}}
function url(x){try{return new URL(typeof x=='string'?x:x&&x.url||'',w.location&&w.location.href||'https://www.youtube.com/')}catch(e){return null}}
function isapi(u){return u&&u.pathname.indexOf('/youtubei/v1/')>=0}
function filtapi(u){return isapi(u)&&/(browse|search|next|player|reel|queue|get_mobile_playback|notification)/.test(u.pathname)}
function payload(u,x,b){if(u&&/\/browse$/.test(u.pathname)){rememberChannelPageVideos(x);learnAccountPlaylistResponse(x,b)}var y=/\/player$/.test(u.pathname)?player(x):filter(x);return w.__ttEnhancePayload?w.__ttEnhancePayload(u,y):y}
function headers(h){var o={};try{var H=new w.Headers(h);H.forEach(function(v,k){o[k]=v});return o}catch(e){}if(obj(h))Object.keys(h).forEach(function(k){o[k]=h[k]});return o}
async function capture(input,init){var u=url(input);if(!isapi(u))return null;cfg();S.key=S.key||u.searchParams.get('key');var h=headers(init&&init.headers||input&&input.headers);if(Object.keys(h).length)S.hdr=h;var t='';if(init&&typeof init.body=='string')t=init.body;else try{if(input&&input.clone)t=await input.clone().text()}catch(e){}var b=json(t);if(b&&b.context)S.ctx=S.ctx||b.context;return{u:u,b:b}}
function apiurl(ep,base){var u=new URL('/youtubei/v1/'+ep,base||(w.location&&w.location.origin)||'https://www.youtube.com/');if(S.key)u.searchParams.set('key',S.key);return u.toString()}
async function post(ep,b,base){var h=Object.assign({},S.hdr);h['content-type']='application/json';delete h['content-length'];var f=S.fetch0||(typeof w.fetch=='function'?w.fetch.bind(w):null);var r=await f(apiurl(ep,base),{method:'POST',credentials:'include',headers:h,body:JSON.stringify(Object.assign({context:S.ctx},b||{}))});if(!r.ok)throw Error(ep+' '+r.status);return r.json()}
async function pages(ep,b,collect,max,base){var q=[b],seen=new Set,n=0;while(q.length&&n<(max||120)){var x=await post(ep,q.shift(),base);collect(x);n++;cont(x).forEach(function(c){if(!seen.has(c)){seen.add(c);q.push({continuation:c})}})}return n}
async function optionalPages(ep,b,collect,max,base){try{return await pages(ep,b,collect,max,base)}catch(e){return 0}}
// Morning eligibility comes only from a fresh, authenticated, complete playlist response.
var MORNING_PLAYLIST='PLxKlPmCFYxy9y498dCB1tIddaxZz3OQCA';
var MORNING_HISTORY_MAX_PAGES=40,MORNING_HISTORY_MAX_VIDEOS=512;
var morningMediaTracking=new WeakMap,morningObservedMedia=new WeakSet;
function morningVideoId(v){return typeof v==='string'&&/^[\w-]{11}$/.test(v)}
function morningPercent(p){return typeof p==='number'&&Number.isFinite(p)&&p>=0&&p<=100}
function morningPageIdentity(x){
  if(!trusted(x))throw Error('morning_history_response_failed');
  var signed=false,scoped=false,invalid=false;
  var tracking=x&&x.responseContext&&x.responseContext.serviceTrackingParams;
  if(!Array.isArray(tracking))throw Error('morning_history_identity_missing');
  tracking.forEach(function(service){
    (Array.isArray(service&&service.params)?service.params:[]).forEach(function(p){
      if(p&&p.key==='logged_in'){if(p.value==='1')signed=true;else invalid=true}
      if(p&&p.key==='browse_id'){if(p.value==='VL'+MORNING_PLAYLIST)scoped=true;else invalid=true}
    });
  });
  if(!signed||!scoped||invalid)throw Error('morning_history_identity_mismatch');
  return true;
}
function resumePercent(x){
  var result=null,invalid=false;
  walk(x,function(o){
    Object.keys(o).forEach(function(k){
      if(/resume.*playback|playback.*progress/i.test(k)&&k!=='thumbnailOverlayResumePlaybackRenderer')
        invalid=true;
    });
    if(Object.prototype.hasOwnProperty.call(o,'thumbnailOverlayResumePlaybackRenderer')){
      var p=o.thumbnailOverlayResumePlaybackRenderer;
      if(!p||!morningPercent(p.percentDurationWatched))invalid=true;
      else if(result!==null&&result!==p.percentDurationWatched)invalid=true;
      else result=p.percentDurationWatched;
    }
  });
  if(invalid)throw Error('morning_history_progress_unrecognized');
  return result;
}
function collectMorningProgress(x,t){
  morningPageIdentity(x);t=t||Object.create(null);
  var found=0;
  walk(x,function(o,p){
    var k=p.length?String(p[p.length-1]):'';
    if(k!=='tileRenderer'||o.contentType!=='TILE_CONTENT_TYPE_VIDEO')return;
    var ep=o.onSelectCommand&&o.onSelectCommand.watchEndpoint,v=ep&&ep.videoId;
    var head=o.header&&o.header.tileHeaderRenderer,overlays=head&&head.thumbnailOverlays;
    if(!morningVideoId(v)||o.contentId!==v||ep.playlistId!==MORNING_PLAYLIST
        ||!Array.isArray(overlays)||!overlays.some(function(z){return z&&z.thumbnailOverlayTimeStatusRenderer}))
      throw Error('morning_history_card_invalid');
    var n=resumePercent(o);
    if(n===null){
      // No resume marker means unstarted only on a successfully authenticated,
      // explicitly scoped, recognized playlist card. Unknown watch markers fail.
      if(/\bwatched\b/i.test(textOf(o)))throw Error('morning_history_progress_unrecognized');
      n=0;
    }
    if(Object.prototype.hasOwnProperty.call(t,v)&&t[v]!==n)
      throw Error('morning_history_conflicting_progress');
    t[v]=n;found++;
  });
  if(!found||Object.keys(t).length>MORNING_HISTORY_MAX_VIDEOS)
    throw Error('morning_history_cards_missing');
  return t;
}
function morningContinuationTokens(x){
  var result=[];
  function add(token){
    if(typeof token!=='string'||!token||token.length>16384)throw Error('morning_history_continuation_invalid');
    if(result.indexOf(token)<0)result.push(token);
  }
  walk(x,function(o){
    Object.keys(o).forEach(function(k){
      if(k==='nextContinuationData')add(o[k]&&o[k].continuation);
      else if(k==='continuationCommand')add(o[k]&&o[k].token);
      else if(k==='continuationItemRenderer'){
        var ep=o[k]&&o[k].continuationEndpoint;
        if(!ep||(!ep.continuationCommand&&!ep.nextContinuationData))
          throw Error('morning_history_continuation_invalid');
      }else if(k==='continuations'){
        if(!Array.isArray(o[k])||o[k].some(function(c){return !c||!c.nextContinuationData}))
          throw Error('morning_history_continuation_unrecognized');
      }else if(/ContinuationData$|continuationItemViewModel/.test(k))
        throw Error('morning_history_continuation_unrecognized');
    });
  });
  return result;
}
function invalidateMorningHistory(phase,now,reason){
  S.morningProgress=null;S.morningProgressAt=0;
  S.morningHistoryStatus={phase:phase,reason:reason||'',pages:0,videos:0,captured_at_ms:now};
  S.morningInvalidationAt=now;
  S.morningStatusUntilElapsed=w.performance.now()+120000;
  S.morningStatusNextElapsed=0;
}
function validMorningHistory(history,now){
  if(!history||history.schema!==1||history.source!=='youtube_playlist'
      ||history.playlist_id!==MORNING_PLAYLIST||history.signed_in!==true||history.complete!==true
      ||!Number.isInteger(history.captured_at_ms)||history.captured_at_ms<=0
      ||now-history.captured_at_ms<0||now-history.captured_at_ms>300000
      ||!Array.isArray(history.covered_ids)||!history.progress||typeof history.progress!=='object'
      ||Array.isArray(history.progress))return false;
  var ids=history.covered_ids,keys=Object.keys(history.progress),seen=new Set;
  if(!ids.length||ids.length>MORNING_HISTORY_MAX_VIDEOS||keys.length!==ids.length)return false;
  return ids.every(function(v){
    if(!morningVideoId(v)||seen.has(v)||!Object.prototype.hasOwnProperty.call(history.progress,v)
        ||!morningPercent(history.progress[v]))return false;
    seen.add(v);return true;
  });
}
function currentMorningTracking(media){
  var r=media&&morningMediaTracking.get(media);
  if(!r||S.ready!==true||S.loggedIn!==true||media!==S.morningMedia||r.generation!==S.morningGeneration
      ||r.video!==S.morningTrackedVideo||routeVideoId()!==r.video||!ok(r.video))return null;
  var source=String(media.currentSrc||media.src||'');
  return r.source&&source!==r.source?null:r;
}

// This lease is renewed by the authenticated session controller. It cannot
// survive a page reload or a lost controller and never resumes media on release.
var SCHOOL_BANNER_REASON='Morning school banner is active.';
var MORNING_STARTUP_REASON='Morning episode is starting.';
// Only a play() actually stopped by the empty startup lease may wait for the
// selected candidate. Renewing/releasing a lease never starts arbitrary media.
var morningStartupDeferred=new Map,morningStartupPlayTimer=null,morningStartupEpoch=0;
var morningNativePlay=new WeakMap;
function mediaSource(media){return String(media.currentSrc||media.src||'')}
function cancelMorningStartupPlay(media,reason){
  var r=morningStartupDeferred.get(media);if(!r)return;
  morningStartupDeferred.delete(media);r.cancelled=true;
  r.reject(new Error(reason||MORNING_STARTUP_REASON));
  if(r.dispatched&&media.paused!==true)try{media.pause()}catch(e){}
  if(!morningStartupDeferred.size&&morningStartupPlayTimer!==null){
    w.clearTimeout(morningStartupPlayTimer);morningStartupPlayTimer=null;
  }
}
function cancelMorningStartupPlays(reason){
  morningStartupDeferred.forEach(function(r,m){cancelMorningStartupPlay(m,reason)});
}
function startupPlayInvalid(r,media){
  if(!morningStartupLeaseLive()||r.epoch!==S.morningStartupLease.epoch
      ||r.request!==S.morningHistoryRequest)return 'Morning startup request cancelled';
  if(w.document&&w.document.hidden)return 'Media window hidden';
  if(routeVideoId()!==r.video)return 'Media route changed';
  if(mediaSource(media)!==r.source)return 'Media source changed';
  if(schoolBannerActive())return SCHOOL_BANNER_REASON;
  var expected=S.morningStartupLease.expected_video_id;
  if(expected&&expected!==r.video)return 'Morning candidate changed';
  if(!r.dispatched&&w.performance.now()>=r.deadline)return 'Morning startup play timed out';
  return '';
}
function flushMorningStartupPlays(){
  if(morningStartupPlayTimer!==null)w.clearTimeout(morningStartupPlayTimer);
  morningStartupPlayTimer=null;
  morningStartupDeferred.forEach(function(r,media){
    var reason=startupPlayInvalid(r,media);
    if(reason){cancelMorningStartupPlay(media,reason);return}
    if(r.dispatched)return;
    var decision=mediaDecision(r.video);
    if(decision==='closed'){cancelMorningStartupPlay(media,REASON);return}
    if(S.morningStartupLease.expected_video_id!==r.video||decision!=='open')return;
    r.dispatched=true;noteMediaInitialization('startup_dispatch',r.video,'open');
    Promise.resolve().then(function(){
      var invalid=startupPlayInvalid(r,media);
      if(r.cancelled||invalid)throw new Error(invalid||'Morning startup request cancelled');
      return r.execute();
    }).then(function(value){
      if(morningStartupDeferred.get(media)!==r)return;
      morningStartupDeferred.delete(media);r.resolve(value);
    },function(error){
      if(morningStartupDeferred.get(media)!==r)return;
      morningStartupDeferred.delete(media);r.reject(error);
    });
  });
  if(morningStartupDeferred.size&&morningStartupPlayTimer===null)
    morningStartupPlayTimer=w.setTimeout(flushMorningStartupPlays,100);
}
function deferMorningStartupPlay(media,video,execute){
  var r=morningStartupDeferred.get(media);
  if(r){var invalid=startupPlayInvalid(r,media);
    if(!invalid&&r.video===video)return r.promise;
    cancelMorningStartupPlay(media,invalid||'Media route changed');
  }
  if(morningStartupDeferred.size>=16)return Promise.reject(new Error('Morning startup play queue is full'));
  var lease=S.morningStartupLease;
  r={video:video,source:mediaSource(media),href:String(w.location&&w.location.href||''),
    epoch:lease.epoch,request:S.morningHistoryRequest,deadline:lease.deadline_elapsed,
    execute:execute,dispatched:false,cancelled:false,resolve:null,reject:null};
  r.promise=new Promise(function(resolve,reject){r.resolve=resolve;r.reject=reject});
  morningStartupDeferred.set(media,r);noteMediaInitialization('startup_wait',video,'pending');
  if(morningStartupPlayTimer===null)morningStartupPlayTimer=w.setTimeout(flushMorningStartupPlays,100);
  return r.promise;
}
function pauseMorningStartupMedia(){
  var lease=S.morningStartupLease,expected=lease&&lease.expected_video_id;
  var mayPlay=expected&&routeVideoId()===expected&&!schoolBannerActive()&&mediaDecision(expected)==='open';
  var list=[],d=w.document;if(S.morningMedia)list.push(S.morningMedia);
  try{var nodes=d&&d.querySelectorAll&&d.querySelectorAll('video,audio');
    for(var i=0;nodes&&i<nodes.length;i++)if(list.indexOf(nodes[i])<0)list.push(nodes[i]);
  }catch(e){}
  list.forEach(function(media){
    if(!media||typeof media.pause!=='function')return;
    var tracked=currentMorningTracking(media),pending=morningNativePlay.get(media);
    if(mayPlay&&(tracked&&tracked.video===expected||pending&&pending.video===expected
        &&pending.source===mediaSource(media)&&pending.epoch===lease.epoch))return;
    // Calling pause on an already-paused element would cancel its intercepted
    // play request. A warming renewal must leave that bounded request pending.
    if(media.paused!==true)try{media.pause()}catch(e){}
  });
}
function clearMorningStartupLease(){
  if(S.morningStartupLeaseTimer!=null)w.clearTimeout(S.morningStartupLeaseTimer);
  S.morningStartupLeaseTimer=null;S.morningStartupLease=null;morningStartupEpoch++;
  cancelMorningStartupPlays('Morning startup lease released');return true;
}
function morningStartupLeaseLive(){
  var lease=S.morningStartupLease;if(!lease)return false;
  if(Date.now()>=lease.deadline_ms||w.performance.now()>=lease.deadline_elapsed){clearMorningStartupLease();return false}
  return true;
}
function morningMediaBlockReason(){
  if(schoolBannerActive())return SCHOOL_BANNER_REASON;
  if(morningStartupLeaseLive()){
    var expected=S.morningStartupLease.expected_video_id;
    if(!expected||routeVideoId()!==expected)return MORNING_STARTUP_REASON;
  }
  return '';
}
function setMorningStartupLease(deadlineMs,expectedVideoId){
  if(deadlineMs===0)return clearMorningStartupLease();
  var now=Date.now(),remaining=deadlineMs-now;
  if(typeof deadlineMs!=='number'||!Number.isFinite(deadlineMs)||remaining<=0||remaining>15000
      ||typeof expectedVideoId!=='string'||expectedVideoId!==''&&!morningVideoId(expectedVideoId))return false;
  var live=morningStartupLeaseLive();if(!live)morningStartupEpoch++;
  if(S.morningStartupLeaseTimer!=null)w.clearTimeout(S.morningStartupLeaseTimer);
  var lease={deadline_ms:deadlineMs,deadline_elapsed:w.performance.now()+remaining,expected_video_id:expectedVideoId,epoch:morningStartupEpoch};
  S.morningStartupLease=lease;
  S.morningStartupLeaseTimer=w.setTimeout(function(){if(S.morningStartupLease===lease)clearMorningStartupLease()},remaining);
  pauseMorningStartupMedia();flushMorningStartupPlays();
  return true;
}
function clearSchoolBannerLease(){
  if(S.schoolBannerLeaseTimer!=null)w.clearTimeout(S.schoolBannerLeaseTimer);
  S.schoolBannerLeaseTimer=null;S.schoolBannerLease=null;return true;
}
function schoolBannerLeaseLive(){
  var lease=S.schoolBannerLease;
  if(!lease)return false;
  if(Date.now()>=lease.deadline_ms||w.performance.now()>=lease.deadline_elapsed){
    clearSchoolBannerLease();return false;
  }
  return true;
}
function schoolBannerActive(){return schoolBannerLeaseLive()&&S.schoolBannerLease.active===true}
function pauseSchoolMedia(){
  cancelMorningStartupPlays(SCHOOL_BANNER_REASON);
  var list=[],d=w.document;
  if(S.morningMedia)list.push(S.morningMedia);
  try{var nodes=d&&d.querySelectorAll&&d.querySelectorAll('video,audio');
    for(var i=0;nodes&&i<nodes.length;i++)if(list.indexOf(nodes[i])<0)list.push(nodes[i]);
  }catch(e){}
  list.forEach(function(m){if(m&&typeof m.pause==='function')try{m.pause()}catch(e){}});
}
function activateSchoolBannerForEnd(v){
  if(!schoolBannerLeaseLive()||S.schoolBannerLease.expected_video_id!==v)return false;
  S.schoolBannerLease.active=true;S.schoolBannerLease.awaiting_end=false;pauseSchoolMedia();return true;
}
function setSchoolBannerLease(deadlineMs,expectedVideoId,awaitingEnd){
  if(deadlineMs===0)return clearSchoolBannerLease();
  var now=Date.now(),remaining=deadlineMs-now;
  if(typeof deadlineMs!=='number'||!Number.isFinite(deadlineMs)||remaining<=0||remaining>15000
      ||typeof awaitingEnd!=='boolean'
      ||!morningVideoId(expectedVideoId)&&!(expectedVideoId===''&&awaitingEnd===false))return false;
  var wasActive=schoolBannerLeaseLive()&&S.schoolBannerLease.expected_video_id===expectedVideoId
    &&S.schoolBannerLease.active===true;
  if(S.schoolBannerLeaseTimer!=null)w.clearTimeout(S.schoolBannerLeaseTimer);
  S.schoolBannerLease={deadline_ms:deadlineMs,deadline_elapsed:w.performance.now()+remaining,
    expected_video_id:expectedVideoId,awaiting_end:awaitingEnd&&!wasActive,active:!awaitingEnd||wasActive};
  var lease=S.schoolBannerLease;
  S.schoolBannerLeaseTimer=w.setTimeout(function(){
    if(S.schoolBannerLease===lease)clearSchoolBannerLease();
  },remaining);
  var ended=S.morningLastEnded;
  if(awaitingEnd&&ended&&ended.video_id===expectedVideoId&&ended.playing==='ended'
      &&now-ended.sample_wall_ms>=0&&now-ended.sample_wall_ms<=5000)
    activateSchoolBannerForEnd(expectedVideoId);
  if(schoolBannerActive())pauseSchoolMedia();
  return true;
}
function noteMorningEnded(media){
  var r=currentMorningTracking(media);
  if(!r||r.ended||media.ended!==true)return false;
  var position=Number(media.currentTime),duration=Number(media.duration);
  if(!Number.isFinite(position)||position<0||!Number.isFinite(duration)||duration<=0)return false;
  r.ended=true;
  S.morningLastEnded={video_id:r.video,position_seconds:position,duration_seconds:duration,
    playing:'ended',sample_wall_ms:Date.now()};
  S.morningPlaying=null;
  activateSchoolBannerForEnd(r.video);
  writeMorningStatus();return true;
}
function schoolMediaEvent(event){
  if(morningMediaBlockReason()&&event&&event.target&&typeof event.target.pause==='function')
    try{event.target.pause()}catch(e){}
}
function watchMorningMediaEvents(media){
  if(!media||typeof media.addEventListener!=='function'||morningObservedMedia.has(media))return;
  morningObservedMedia.add(media);
  media.addEventListener('ended',function(){noteMorningEnded(media)},true);
  media.addEventListener('play',schoolMediaEvent,true);
  media.addEventListener('playing',schoolMediaEvent,true);
}
function installSchoolMediaEvents(){
  var d=w.document;if(!d||typeof d.addEventListener!=='function'||S.schoolMediaEventsDocument===d)return;
  S.schoolMediaEventsDocument=d;
  // Capture non-bubbling ended before YouTube's target autoplay handlers.
  d.addEventListener('ended',function(e){if(e)noteMorningEnded(e.target)},true);
  d.addEventListener('play',schoolMediaEvent,true);
  d.addEventListener('playing',schoolMediaEvent,true);
}
// One status payload keeps account progress and playback evidence from overwriting each other.
function morningIsActive(v) {
  var m=S.morningMedia;
  return !!m && v===S.morningTrackedVideo && routeVideoId()===v && ok(v)
    && m.paused===false && m.ended!==true && Number(m.readyState)>=2;
}
function morningProgressFresh(now) {
  if(S.morningProgress&&S.morningHistoryAccountIdentity!==morningAccountIdentity()){
    invalidateMorningHistory('unavailable',now);
  }
  return S.ready===true&&S.loggedIn===true&&!!S.morningProgress
    &&validMorningHistory(S.morningProgress.__youtube_history,now);
}
function morningStatusPayload(now) {
  var body='yumi=morning-sesame&ms_state=idle';
  var fresh=morningProgressFresh(now);
  if(S.morningInvalidationAt)
    body+='&ms_progress='+encodeURIComponent('{}')+'&ms_progress_at='+S.morningInvalidationAt;
  else if(fresh)
    body+='&ms_progress='+encodeURIComponent(JSON.stringify(S.morningProgress))
      +'&ms_progress_at='+S.morningProgressAt;
  else if(S.morningHistoryStatus&&/^(loading|unavailable)$/.test(S.morningHistoryStatus.phase))
    body+='&ms_progress='+encodeURIComponent('{}')+'&ms_progress_at='+S.morningHistoryStatus.captured_at_ms;
  var historyStatus=S.morningHistoryStatus;
  if(historyStatus){
    body+='&ms_history_phase='+encodeURIComponent(historyStatus.phase||'unavailable');
    body+='&ms_history_reason='+encodeURIComponent(historyStatus.reason||'');
    body+='&ms_history_attempts='+(Number(historyStatus.attempts)||0);
    body+='&ms_history_pages='+(Number(historyStatus.pages)||0);
  }
  var v=S.morningPlaying;
  if(v&&morningIsActive(v)&&now-S.morningPlayingAt>=-1000&&now-S.morningPlayingAt<=5000) {
    body=body.replace('ms_state=idle','ms_state=playing');
    body+='&ms_playing='+encodeURIComponent(v)+'&ms_playing_at='+S.morningPlayingAt
      +'&ms_position='+S.morningPlayingPosition;
  }
  return body;
}
async function writeMorningStatus() {
  var f=S.fetch0||(typeof w.fetch==='function'?w.fetch.bind(w):null);
  if(!f)return false;
  if(S.morningStatusPhase==='posting'){S.morningStatusQueued=true;return false}
  S.morningStatusPhase='posting';
  S.morningStatusNextElapsed=w.performance.now()+2000;
  var body=morningStatusPayload(Date.now()),invalidation=S.morningInvalidationAt,timer;
  try {
    await Promise.race([f('http://localhost:8012/apps/YouTube',{method:'POST',mode:'no-cors',
      headers:{'content-type':'application/x-www-form-urlencoded'},body:body}),
      new Promise(function(resolve,reject){timer=w.setTimeout(function(){reject(Error('morning_status_timeout'))},1000)})]);
    if(invalidation&&S.morningInvalidationAt===invalidation){
      S.morningInvalidationAt=0;
      if(S.morningProgress)S.morningStatusQueued=true;
    }
    return true;
  } catch(e) { return false; }
  finally {
    if(timer!=null)w.clearTimeout(timer);
    S.morningStatusPhase='idle';
    if(S.morningStatusQueued){
      S.morningStatusQueued=false;
      w.setTimeout(function(){writeMorningStatus()},0);
    }
  }
}
function pumpMorningStatus() {
  var elapsed=w.performance.now(),d=w.document;
  if(!d||d.hidden||S.ready!==true||S.loggedIn!==true||S.morningStatusPhase==='posting')return;
  var fresh=morningProgressFresh(Date.now()),invalid=!!S.morningInvalidationAt;
  if(!(elapsed<Number(S.morningStatusUntilElapsed))
      ||elapsed<Number(S.morningStatusNextElapsed||0)||(!fresh&&!invalid))return;
  writeMorningStatus();
}
async function writeMorningProgress(m) {
  var history=m&&m.__youtube_history;
  if(S.ready!==true||S.loggedIn!==true||!validMorningHistory(history,Date.now()))return false;
  // Copy only the authenticated envelope; no local or legacy fields participate.
  S.morningProgress={__youtube_history:JSON.parse(JSON.stringify(history))};
  S.morningProgressAt=history.captured_at_ms;
  S.morningHistoryAccountIdentity=morningAccountIdentity();
  S.morningStatusUntilElapsed=w.performance.now()+120000;
  return writeMorningStatus();
}
async function writeMorningPlaying(v) {
  if(v!==S.morningTrackedVideo)return false;
  var m=S.morningMedia,position=m?Number(m.currentTime):NaN;
  var advanced=morningIsActive(v)&&Number.isFinite(position)
    &&position>Number(S.morningPreviousPosition)+0.05;
  S.morningPreviousPosition=position;
  S.morningPlaying=advanced?v:null;
  if(advanced) {
    S.morningPlayingAt=Date.now();
    S.morningPlayingPosition=position;
  }
  await writeMorningStatus();
  return advanced;
}
function publishMorningPlaying(v,media) {
  if(!morningVideoId(v)||!media||S.ready!==true||S.loggedIn!==true||routeVideoId()!==v||!ok(v))return false;
  if(typeof media.tagName==='string'&&media.tagName.toUpperCase()!=='VIDEO')return false;
  var generation=(S.morningGeneration||0)+1;
  S.morningGeneration=generation;
  S.morningTrackedVideo=v;
  S.morningMedia=media;
  S.morningPreviousPosition=Number(media.currentTime);
  S.morningPlaying=null;
  morningMediaTracking.set(media,{video:v,generation:generation,source:String(media.currentSrc||media.src||''),
    previousPosition:Number(media.currentTime),ended:false});
  installSchoolMediaEvents();watchMorningMediaEvents(media);
  var observeUntil=Date.now()+180000;
  // A resolved play() promise is not proof of moving video.
  writeMorningStatus();
  function tick() {
    if(S.morningGeneration!==generation)return;
    writeMorningPlaying(v);
    // Buffering or a short pause must not permanently disable confirmation.
    if(morningIsActive(v))observeUntil=Date.now()+180000;
    if(S.morningGeneration===generation && routeVideoId()===v && ok(v)
        && media.ended!==true && Date.now()<observeUntil
        && typeof w.setTimeout==='function')w.setTimeout(tick,2000);
  }
  if(typeof w.setTimeout==='function')w.setTimeout(tick,2000);
  return true;
}
function morningAccountIdentity(){
  var values=[];Object.keys(S.hdr).sort().forEach(function(k){
    if(/^(authorization|x-goog-authuser|x-goog-pageid)$/i.test(k))values.push(k.toLowerCase()+':'+S.hdr[k]);
  });
  return JSON.stringify([values,S.ctx&&S.ctx.user||null]);
}
async function morningHistoryPost(body,base,deadline){
  var remaining=Math.min(8000,deadline-Date.now()),timer;
  if(remaining<=0)throw Error('morning_history_timeout');
  try{return await Promise.race([post('browse',body,base),new Promise(function(resolve,reject){
    timer=w.setTimeout(function(){reject(Error('morning_history_timeout'))},remaining);
  })])}finally{if(timer!=null)w.clearTimeout(timer)}
}
function morningHistoryReason(error){
  var text=String(error&&error.message||error||'');
  if(/^morning_history_[a-z_]+$/.test(text))return text;
  if(/\b401\b/.test(text))return 'morning_history_http_401';
  if(/\b403\b/.test(text))return 'morning_history_http_403';
  if(/\b5[0-9][0-9]\b/.test(text))return 'morning_history_http_5xx';
  return 'morning_history_network';
}
function morningHistoryWindowCurrent(generation){
  if(generation==null)return true;
  var h=S.morningHistoryWindow,d=w.document;
  return !!h&&h.generation===generation&&!h.closed&&d&&!d.hidden
    &&w.performance.now()<h.deadlineElapsed&&Date.now()<h.deadlineWall;
}
async function publishMorningProgress(env){
  if(S.morningProgressTask)return S.morningProgressTask;
  env=env||{};var context=S.ctx,identity=morningAccountIdentity();
  var deadline=Math.min(Date.now()+20000,Number.isFinite(env.deadline_ms)?env.deadline_ms:Infinity);
  S.morningRefreshAtElapsed=w.performance.now();
  // A refresh invalidates the old selection snapshot until all pages succeed.
  invalidateMorningHistory('loading',Date.now(),'morning_history_fetching');
  var task=(async function(){
    var progress=Object.create(null),queue=[{browseId:'VL'+MORNING_PLAYLIST}],seen=new Set,pagesRead=0;
    try{
      await writeMorningStatus();
      if(!context||S.ready!==true||S.loggedIn!==true||!authed())
        throw Error('morning_history_not_signed_in');
      while(queue.length){
        if(!morningHistoryWindowCurrent(env.window_generation))throw Error('morning_history_request_cancelled');
        if(pagesRead>=MORNING_HISTORY_MAX_PAGES)throw Error('morning_history_incomplete');
        var page=await morningHistoryPost(queue.shift(),env.apiBase,deadline);
        collectMorningProgress(page,progress);pagesRead++;
        morningContinuationTokens(page).forEach(function(c){
          if(seen.has(c))throw Error('morning_history_repeated_continuation');
          seen.add(c);queue.push({continuation:c});
        });
        if(S.ctx!==context||S.ready!==true||S.loggedIn!==true||!authed()
            ||morningAccountIdentity()!==identity)throw Error('morning_history_account_changed');
      }
      if(!morningHistoryWindowCurrent(env.window_generation))throw Error('morning_history_request_cancelled');
      var history={schema:1,source:'youtube_playlist',playlist_id:MORNING_PLAYLIST,
        captured_at_ms:Date.now(),signed_in:true,complete:true,
        covered_ids:Object.keys(progress),progress:progress};
      var bridgePosted=await writeMorningProgress({__youtube_history:history});
      // A DIAL acknowledgement may arrive after the admitted run or account changed.
      var active=S.morningHistoryWindow;
      if(env.window_generation!=null&&(!active||active.generation!==env.window_generation
          ||active.closed&&active.result!=='ok'))return null;
      if(S.ctx!==context||S.ready!==true||S.loggedIn!==true||!authed()
          ||morningAccountIdentity()!==identity)throw Error('morning_history_account_changed');
      S.morningHistoryStatus={phase:'complete',reason:'ok',pages:pagesRead,videos:history.covered_ids.length,
        captured_at_ms:history.captured_at_ms,bridge_posted:bridgePosted,
        attempts:active&&active.generation===env.window_generation?active.attempts:0};
      return history;
    }catch(e){
      var h=S.morningHistoryWindow;
      if(env.window_generation==null||h&&h.generation===env.window_generation&&(!h.closed||h.result==='ok')){
        invalidateMorningHistory('unavailable',Date.now(),morningHistoryReason(e));
        S.morningHistoryStatus.pages=pagesRead;
        await writeMorningStatus();
      }
      if(typeof console!='undefined'&&console.log)
        console.log('[allowed-only] authenticated playlist history unavailable: '+morningHistoryReason(e));
      return null;
    }
  })();
  S.morningProgressTask=task;
  try{return await task}finally{if(S.morningProgressTask===task)S.morningProgressTask=null}
}
var MORNING_REFRESH_WINDOW_MS=60000,MORNING_REFRESH_MAX_ATTEMPTS=6;
function morningHistorySatisfies(minimum){
  var history=S.morningProgress&&S.morningProgress.__youtube_history;
  return S.ready===true&&S.loggedIn===true&&authed()
    &&S.morningHistoryAccountIdentity===morningAccountIdentity()
    &&validMorningHistory(history,Date.now())&&history.captured_at_ms>=minimum;
}
function finishMorningHistoryWindow(h,reason){
  if(!h||h.closed)return;
  h.closed=true;h.result=reason;if(h.timer!=null)w.clearTimeout(h.timer);h.timer=null;
  if(reason==='ok'&&h.request){h.request.completed=true;h.request.completedIdentity=morningAccountIdentity()}
  if(reason!=='ok'){
    var last=S.morningHistoryStatus&&S.morningHistoryStatus.reason;
    invalidateMorningHistory('unavailable',Date.now(),reason);
    S.morningHistoryStatus.last_error=last||'';
    writeMorningStatus();
  }
  if(S.morningHistoryStatus)S.morningHistoryStatus.attempts=h.attempts;
}
function scheduleMorningHistoryRefresh(h,delay){
  if(!h||h.closed||S.morningHistoryWindow!==h)return;
  if(h.timer!=null)w.clearTimeout(h.timer);
  h.timer=w.setTimeout(function(){h.timer=null;pumpMorningHistoryRefresh()},Math.max(1,delay));
}
function beginMorningHistoryWindow(minimum,deadline,request){
  var previous=S.morningHistoryWindow;
  if(previous){previous.closed=true;if(previous.timer!=null)w.clearTimeout(previous.timer)}
  var now=Date.now(),elapsed=w.performance.now(),budget=Math.min(MORNING_REFRESH_WINDOW_MS,deadline-now);
  var h={generation:(S.morningHistoryGeneration||0)+1,minimum:minimum,
    deadlineWall:now+budget,deadlineElapsed:elapsed+budget,attempts:request?request.attempts:0,nextAt:elapsed,
    request:request||null,
    identity:null,identityAt:elapsed,closed:false,timer:null};
  S.morningHistoryGeneration=h.generation;S.morningHistoryWindow=h;
  invalidateMorningHistory('loading',now,'morning_history_waiting_auth');
  writeMorningStatus();pumpMorningHistoryRefresh();return h;
}
function pumpMorningHistoryRefresh(){
  var h=S.morningHistoryWindow;if(!h||h.closed)return;
  var now=Date.now(),elapsed=w.performance.now(),d=w.document;
  if(elapsed>=h.deadlineElapsed||now>=h.deadlineWall){finishMorningHistoryWindow(h,'morning_history_retry_deadline');return}
  if(morningHistorySatisfies(h.minimum)){finishMorningHistoryWindow(h,'ok');return}
  if(!d||d.hidden||S.ready!==true||S.loggedIn!==true||!S.ctx||!authed()){
    if(S.morningHistoryStatus){S.morningHistoryStatus.reason=d&&d.hidden?'morning_history_waiting_visible':'morning_history_waiting_auth';S.morningHistoryStatus.attempts=h.attempts}
    scheduleMorningHistoryRefresh(h,Math.min(500,h.deadlineElapsed-elapsed));return;
  }
  if(S.morningProgressTask){scheduleMorningHistoryRefresh(h,250);return}
  var identity=morningAccountIdentity();
  if(identity!==h.identity){h.identity=identity;h.identityAt=elapsed}
  if(elapsed-h.identityAt<750){
    if(S.morningHistoryStatus)S.morningHistoryStatus.reason='morning_history_auth_settling';
    scheduleMorningHistoryRefresh(h,750-(elapsed-h.identityAt));return;
  }
  if(elapsed<h.nextAt){scheduleMorningHistoryRefresh(h,h.nextAt-elapsed);return}
  if(h.attempts>=MORNING_REFRESH_MAX_ATTEMPTS){finishMorningHistoryWindow(h,'morning_history_retries_exhausted');return}
  h.attempts++;
  if(h.request)h.request.attempts=h.attempts;
  publishMorningProgress({deadline_ms:h.deadlineWall,window_generation:h.generation}).then(function(){
    if(S.morningHistoryWindow!==h||h.closed)return;
    if(S.morningHistoryStatus)S.morningHistoryStatus.attempts=h.attempts;
    if(morningHistorySatisfies(h.minimum)){finishMorningHistoryWindow(h,'ok');return}
    var delays=[2000,4000,8000,12000,12000];
    h.nextAt=w.performance.now()+delays[Math.min(h.attempts-1,delays.length-1)];
    scheduleMorningHistoryRefresh(h,Math.min(h.nextAt-w.performance.now(),h.deadlineElapsed-w.performance.now()));
  });
}
function validMorningHistoryRequest(request){
  var now=Date.now();
  return !!request&&typeof request.run_id==='string'&&/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(request.run_id)
    &&Number.isInteger(request.started_at_ms)&&request.started_at_ms>0&&request.started_at_ms<=now+1000
    &&now-request.started_at_ms<=300000&&Number.isInteger(request.deadline_ms)
    &&request.deadline_ms>now&&request.deadline_ms>request.started_at_ms
    &&request.deadline_ms-request.started_at_ms<=300000;
}
function sameMorningHistoryRequest(a,b){
  return !!a&&!!b&&a.run_id===b.run_id&&a.started_at_ms===b.started_at_ms&&a.deadline_ms===b.deadline_ms;
}
function requestMorningProgress(request){
  var d=w.document;if(!d||d.hidden||!validMorningHistoryRequest(request))return false;
  var previous=S.morningHistoryRequest;
  if(previous&&previous.run_id===request.run_id){
    if(!sameMorningHistoryRequest(previous,request))return false;
    var h=S.morningHistoryWindow;
    // Account rotation after a successful capture is a new need for evidence, but
    // keeps the original run deadline and cumulative six-attempt limit.
    if(previous.completed&&!morningHistorySatisfies(previous.started_at_ms)
        &&previous.completedIdentity!==morningAccountIdentity()
        &&(!h||h.request!==previous||h.closed&&h.result==='ok'))
      beginMorningHistoryWindow(previous.started_at_ms,previous.deadline_ms,previous);
    return true;
  }
  if(previous&&request.started_at_ms<previous.started_at_ms)return false;
  var admitted={run_id:request.run_id,started_at_ms:request.started_at_ms,deadline_ms:request.deadline_ms,
    attempts:0,completed:false,completedIdentity:null};
  cancelMorningStartupPlays('Morning startup run changed');
  S.morningHistoryRequest=admitted;
  if(morningHistorySatisfies(request.started_at_ms)){
    admitted.completed=true;admitted.completedIdentity=morningAccountIdentity();
  }else beginMorningHistoryWindow(request.started_at_ms,request.deadline_ms,admitted);
  return true;
}
// The controller reads this snapshot directly; DIAL is an optional compatibility publication.
function morningHistoryForRun(request){
  var d=w.document;
  if(!d||d.hidden||!validMorningHistoryRequest(request)||!sameMorningHistoryRequest(S.morningHistoryRequest,request)
      ||!morningHistorySatisfies(request.started_at_ms))return null;
  return JSON.parse(JSON.stringify(S.morningProgress));
}
function refreshMorningProgressOnFocus(){
  var d=w.document,now=Date.now();if(!d||d.hidden)return;
  if(validMorningHistoryRequest(S.morningHistoryRequest)){requestMorningProgress(S.morningHistoryRequest);return}
  if(S.morningHistoryWindow&&!S.morningHistoryWindow.closed){pumpMorningHistoryRefresh();return}
  if(Number.isFinite(S.morningFocusRefreshAt)&&now-S.morningFocusRefreshAt<1000)return;
  S.morningFocusRefreshAt=now;
  beginMorningHistoryWindow(now,now+MORNING_REFRESH_WINDOW_MS);
}
function watchMorningLifecycle(){
  if(S.morningLifecycle==='watching'||!w.document||!w.addEventListener)return;
  S.morningLifecycle='watching';
  w.addEventListener('focus',refreshMorningProgressOnFocus);
  w.document.addEventListener('visibilitychange',refreshMorningProgressOnFocus);
}
async function accountLibraries(env){env=env||{};var cards=S.savedPlaylists,collections=new Set,librarySource={ownership:'account',provenance:'account-library',endpoint:'FEmusic_library'},collectionSource={ownership:'account',provenance:'account-collection',endpoint:'FEmusic_library',collection:'artist-album'};await optionalPages('browse',{browseId:'FElibrary',params:'FEplaylist_aggregation'},function(x){collectPlaylistIds(x,cards)},8,env.apiBase);await Promise.all(Array.from(cards).map(function(id){return optionalPages('browse',{browseId:id},collectPlaylistVideoIds,0,env.apiBase)}));var musicPage=function(source){return function(x){collectMusicLibraryVideoIds(x,S.music,source);collectMusicCollectionVideoIds(x,S.music,collectionSource);collectMusicCollectionBrowseIds(x,collections,collectionSource)}};await optionalPages('browse',{browseId:'FEmusic_library',params:'FEmusic_last_played'},musicPage(librarySource),12,env.apiBase);await Promise.all(Array.from(collections).map(function(id){return optionalPages('browse',{browseId:id},function(x){return collectMusicLibraryVideoIds(x,S.music,{ownership:'account',provenance:'account-collection',endpoint:id,collection:'artist-album'})},12,env.apiBase)}));await optionalPages('browse',{browseId:'FEstorefront',params:'ogUCKAQ='},collectPurchasedVideoIds,12,env.apiBase);return S.accountVideos}
function startBackgroundEnrichment(env){env=env||{};var run=function(){Promise.all([optionalPages('browse',{browseId:'VLLL'},liked,8,env.apiBase),optionalPages('browse',{browseId:'FEchannels'},channels,8,env.apiBase),post('guide',{},env.apiBase).then(function(x){guide(x)}).catch(function(){}),accountLibraries(env),publishMorningProgress(env)]).catch(function(){})};if(typeof w.setTimeout=='function')w.setTimeout(run,2500);else Promise.resolve().then(run)}async function boot(env){env=env||{};cfg();if(!S.ctx)throw Error('no context');var lo=false,so=false,errs=[];await Promise.all([post('browse',{browseId:'VLLL'},env.apiBase).then(function(x){liked(x);lo=true}).catch(function(e){errs.push('liked '+e)}),post('browse',{browseId:'FEchannels'},env.apiBase).then(function(x){channels(x);so=true}).catch(function(e){errs.push('subscriptions '+e)})]);if(!lo||!so){for(var i=0;i<errs.length;i++)S.errors.push(errs[i]);throw Error('allowlist partial: '+(errs[errs.length-1]||'required source unavailable'))}S.loggedIn=true;S.ready=true;startBackgroundEnrichment(env);return S}

function sleep(ms){return new Promise(function(r){setTimeout(r,ms)})}
function retryDelaySchedule(maxAttempts,baseDelayMs,capMs){var d=[];for(var i=1;i<maxAttempts;i++){d.push(Math.min(baseDelayMs*Math.pow(2,i-1),capMs))}return d}
async function bootWithRetry(env){env=env||{};cfg();S.booting=true;S.ready=false;renderStatus();var max=env.maxAttempts||5,base=env.baseDelayMs||1000,cap=env.capMs||16000,sched=retryDelaySchedule(max,base,cap),last;for(var i=0;i<max;i++){try{await boot(env);S.errors=[];S.booting=false;renderStatus();try{sweepDom()}catch(e){S.errors.push('dom sweep '+e)}return{ok:true,attempts:i+1}}catch(e){S.retries++;last=e;S.errors.push('boot attempt '+(i+1)+': '+e);if(/\b(?:401|403)\b/.test(String(e)))S.loggedIn=false;if(i<sched.length)await sleep(sched[i])}}S.booting=false;var genuine=S.errors.some(function(e){return /\b(?:401|403)\b/.test(String(e))});if(genuine){S.loggedIn=false;S.ready=true;renderStatus();if(typeof console!='undefined'&&console.log)console.log('[allowed-only] genuine unauthenticated proof: everything blocked');return{ok:false,attempts:max,guest:true,error:String(last)}}S.ready=false;renderStatus();return{ok:false,attempts:max,error:String(last)}}

async function heal(env){env=env||{};for(var i=0;i<(env.healCycles||30);i++){await sleep(env.healDelayMs||60000);if(S.ready&&!S.errors.length&&(S.liked.size||S.subs.size))return;var r=await bootWithRetry(env);if(r.ok||r.guest)return}}
function ensure(){if(S.ready)return Promise.resolve(S);if(S.p)return S.p;cfg();if(!S.ctx)return Promise.resolve(null);S.p=bootWithRetry({}).then(function(r){renderStatus();if(typeof console!='undefined'&&console.log)console.log('[allowed-only] boot '+(r.ok?'ready':'unavailable, self-healing')+' ('+S.liked.size+' liked, '+S.subs.size+' subscribed)');if(!r.ok)heal({});return S});return S.p}
async function verifyDirectVideo(v){if(!v)return false;if(S.ready&&ok(v))return true;try{cfg();if(S.ctx)ensure();for(var i=0;i<180&&!S.ready;i++){await sleep(1000);cfg();if(S.ctx)ensure()}if(!S.ready||S.loggedIn===false)return false;if(ok(v))return true;var x=await post('player',{videoId:v}),d=x&&x.videoDetails||{},id=d.videoId,c=uc(d.channelId)||uc(d.externalChannelId);if(id!==v||!c)return false;S.v2c.set(v,c);return ok(v,c)}catch(e){S.errors.push('direct '+e);return false}}
function gateDirectRoute(){try{if(R.busy||!w.location)return;var v=routeVideoId();if(!v||ok(v))return;R.busy=true;R.pending=v;R.hash=w.location.hash||('#/watch?v='+v);w.location.hash='#/';verifyDirectVideo(v).then(function(allowed){var same=R.pending===v,h=R.hash;R.busy=false;if(same){R.pending=null;R.hash=null}if(allowed&&same&&h)w.location.hash=h;else if(same&&typeof console!='undefined'&&console.log)console.log('[allowed-only] direct route blocked '+v)})}catch(e){R.busy=false}}
function installRouteGate(){if(R.installed)return;R.installed=true;if(w.addEventListener)w.addEventListener('hashchange',function(){if(w.setTimeout)w.setTimeout(gateDirectRoute,0);else gateDirectRoute()});if(w.setTimeout)w.setTimeout(gateDirectRoute,0)}

async function response(r,u,b){try{var x=json(await r.clone().text());if(!x)return r;S.stripped=0;x=payload(u,x,b);if(S.stripped>0&&typeof console!='undefined'&&console.log)console.log('[allowed-only] stripped '+S.stripped+' video(s) from '+u.pathname);var h=new w.Headers(r.headers);h.delete('content-length');h.delete('content-encoding');return new w.Response(JSON.stringify(x),{status:r.status,statusText:r.statusText,headers:h})}catch(e){return r}}
function installFetch(){if(typeof w.fetch!='function')return;S.fetch0=w.fetch.bind(w);w.fetch=async function(input,init){var bu=url(input);if(w.__ttShouldBlockAdRequest&&w.__ttShouldBlockAdRequest(bu)){if(typeof w.Response=='function')return new w.Response(null,{status:204,statusText:'No Content'});return{ok:true,status:204,statusText:'No Content'}}var c=await capture(input,init||{}),u=c&&c.u,b=c&&c.b,p=u?ensure():Promise.resolve(null),r=await S.fetch0(input,init);if(!u||!filtapi(u))return r;await p;if(!S.ready)await ensure();return response(r,u,b)};try{w.fetch.__tt=1}catch(e){}}
function installXhr(){if(typeof w.XMLHttpRequest!='function'||!S.fetch0)return;var p=w.XMLHttpRequest.prototype;if(p.__ao)return;p.__ao=1;var O=p.open,D=p.send,H=p.setRequestHeader,M=new WeakMap;function m(x){var y=M.get(x);if(!y){y={h:{}};M.set(x,y)}return y}function mark(g){try{g.__aog=1}catch(e){}return g}S.arm=function(){try{return armXhr()}catch(e){return false}};function armXhr(){if(!S.fetch0||typeof w.XMLHttpRequest!='function')return false;var q=w.XMLHttpRequest.prototype;if(!q)return false;if(S.xhrArmed)return true;var RT=Object.getOwnPropertyDescriptor(q,'responseText'),R=Object.getOwnPropertyDescriptor(q,'response'),ok=true;try{if(!(RT&&RT.get&&RT.get.__aog)){if(RT&&RT.get&&RT.configurable!==false){Object.defineProperty(q,'responseText',{configurable:true,get:mark(function(){return text(this,RT.get.call(this))})})}else ok=false}}catch(e){ok=false}try{if(!(R&&R.get&&R.get.__aog)){if(R&&R.get&&R.configurable!==false){Object.defineProperty(q,'response',{configurable:true,get:mark(function(){var s=R.get.call(this);if(!isapi(m(this).u))return s;if(this.responseType=='json'&&obj(s)){var y=m(this);if(y.fj!==undefined)return y.fj;S.stripped=0;y.fj=payload(y.u,s,y.b);if(S.stripped>0&&typeof console!='undefined'&&console.log)console.log('[allowed-only] stripped '+S.stripped+' video(s) from '+y.u.pathname);return y.fj}if((this.responseType==''||this.responseType=='text')&&typeof s=='string')return text(this,s);return s})})}else ok=false}}catch(e){ok=false}S.xhrArmed=ok;if(typeof console!='undefined'&&console.log)console.log('[allowed-only] xhr response interception '+(ok?'armed':'pending retry'));return ok}p.open=function(method,u){var y=m(this);y.u=url(u);y.b=null;y.ft=y.fj=undefined;return O.apply(this,arguments)};p.setRequestHeader=function(k,v){m(this).h[k]=v;return H.apply(this,arguments)};try{p.__ttWrap=1}catch(e){}p.send=function(b){var x=this,y=m(x);if(w.__ttShouldBlockAdRequest&&w.__ttShouldBlockAdRequest(y.u)){try{x.abort()}catch(e){}return}if(isapi(y.u)){if(Object.keys(y.h).length)S.hdr=Object.assign({},y.h);cfg();S.key=S.key||y.u.searchParams.get('key');var z=typeof b=='string'&&json(b);y.b=z;if(z&&z.context)S.ctx=S.ctx||z.context}if(!S.xhrArmed)armXhr();if(filtapi(y.u)){var q=w.XMLHttpRequest.prototype,RT=Object.getOwnPropertyDescriptor(q,'responseText'),R=Object.getOwnPropertyDescriptor(q,'response');try{if(!(Object.getOwnPropertyDescriptor(x,'responseText')||{}).__aog&&RT&&RT.get&&RT.get.__aog){Object.defineProperty(x,'responseText',{configurable:true,get:mark(function(){return text(x,RT.get.call(x))})})}}catch(e){}try{if(!(Object.getOwnPropertyDescriptor(x,'response')||{}).__aog&&R&&R.get&&R.get.__aog){Object.defineProperty(x,'response',{configurable:true,get:mark(function(){var s=R.get.call(x);if(this.responseType=='json'&&obj(s)){var yy=m(x);if(yy.fj!==undefined)return yy.fj;S.stripped=0;yy.fj=payload(yy.u,s,yy.b);if(S.stripped>0&&typeof console!='undefined'&&console.log)console.log('[allowed-only] stripped '+S.stripped+' video(s) from '+yy.u.pathname);return yy.fj}if((this.responseType==''||this.responseType=='text')&&typeof s=='string')return text(x,s);return s})})}}catch(e){}}if(filtapi(y.u)&&!S.ready){var q2=ensure();if(!q2&&S.ctx===null&&typeof console!='undefined'&&console.log)console.log('[allowed-only] api request before context: filtering on read');if(q2&&q2.then){q2.then(function(){D.call(x,b)},function(){D.call(x,b)});return}}return D.call(x,b)};function text(x,s){var y=m(x);if(!filtapi(y.u)||typeof s!='string'||!s)return s;if(y.ft!==undefined)return y.ft;var z=json(s);if(!z)return s;S.stripped=0;y.fj=payload(y.u,z,y.b);if(S.stripped>0&&typeof console!='undefined'&&console.log)console.log('[allowed-only] stripped '+S.stripped+' video(s) from '+y.u.pathname);return y.ft=JSON.stringify(y.fj)}armXhr();try{if(typeof w.setTimeout=='function'){w.setTimeout(function(){if(!S.xhrArmed)armXhr()},100);w.setTimeout(function(){if(!S.xhrArmed)armXhr()},500);w.setTimeout(function(){if(!S.xhrArmed)armXhr()},2000);w.setTimeout(function(){if(!S.xhrArmed)armXhr()},6000)}}catch(e){}}
function bridge(){try{if(w.HTMLMediaElementExtension&&w.HTMLMediaElement&&w.HTMLMediaElement.prototype)w.HTMLMediaElement.prototype.canPlayType=w.HTMLMediaElementExtension.canPlayType}catch(e){}}
function routeVideoId(){try{var l=w.location;if(!l)return null;var u=new URL(l.href||'https://www.youtube.com/');var v=u.searchParams.get('v');if(v&&/^[\w-]+$/.test(v))return v;var pm=/\/watch\/([\w-]+)/.exec(u.pathname);if(pm)return pm[1];var s=u.hash||'';if(s.indexOf('watch')>=0){var qi=s.indexOf('?');if(qi>=0){try{var hv=new URLSearchParams(s.slice(qi+1)).get('v');if(hv&&/^[\w-]+$/.test(hv))return hv}catch(e){}}var hm=/watch\/([\w-]+)/.exec(s);if(hm)return hm[1]}return null}catch(e){return null}}
function mediaDecision(v){
  var decision;
  if(S.ready!==true)decision='pending';
  else if(ok(v))decision='open';
  else if(S.loggedIn===false)decision='closed';
  // The account list can be ready before the player response has identified
  // this video's owner. Unknown ownership is still pending; the real player
  // response/channel evidence must supply positive permission before playback.
  else if(v&&!S.v2c.has(v))decision='pending';
  else decision='closed';
  S.mediaGate=decision;return decision;
}
function mediaBlockedLog(){if(typeof console!='undefined'&&console.log)console.log('[allowed-only] media blocked '+routeVideoId())}
// A pending allowlist is not permission to play. It also must not throw from
// YouTube's synchronous MediaSource constructor: that aborts initialization and
// leaves an empty player even when the account becomes ready a moment later.
// Only inert object-URL allocation is allowed while pending. Source attachment,
// load and play wait for positive authorization of the same route.
var mediaPending=new Map,mediaPendingTimer=null;
var MEDIA_PENDING_LIMIT=16,MEDIA_PENDING_MS=180000;
function mediaPendingNow(){return w.performance.now()}
function noteMediaInitialization(stage,video,decision){
  var events=S.mediaInitializationEvents||(S.mediaInitializationEvents=[]);
  events.push({stage:stage,video:video||null,decision:decision,at_ms:Math.round(mediaPendingNow())});
  if(events.length>16)events.shift();
}
function cancelPendingPlay(media,reason){
  var r=mediaPending.get(media);if(!r||!r.play)return;
  var reject=r.reject;r.play=null;r.promise=null;r.resolve=null;r.reject=null;
  if(reject)reject(new Error(reason));
}
function cancelPendingMedia(media,reason){
  var r=mediaPending.get(media);if(!r)return;
  noteMediaInitialization('cancel',r.video,reason||REASON);
  mediaPending.delete(media);r.phase='cancelled';
  if(r.reject)r.reject(new Error(reason||REASON));
  if(!mediaPending.size&&mediaPendingTimer!==null){
    w.clearTimeout(mediaPendingTimer);mediaPendingTimer=null;
  }
}
function flushPendingMedia(){
  if(mediaPendingTimer!==null)w.clearTimeout(mediaPendingTimer);
  mediaPendingTimer=null;
  mediaPending.forEach(function(r,media){
    if(r.phase!=='waiting')return;
    if(w.document&&w.document.hidden){cancelPendingMedia(media,'Media window hidden');return}
    if(routeVideoId()!==r.video){cancelPendingMedia(media,'Media route changed');return}
    var decision=mediaDecision(r.video);
    if(decision==='closed'){cancelPendingMedia(media,REASON);return}
    if(mediaPendingNow()>=r.deadline){cancelPendingMedia(media,'Media authorization timed out');return}
    if(decision!=='open')return;
    noteMediaInitialization('dispatch',r.video,decision);
    mediaPending.delete(media);r.phase='running';
    try{
      if(r.source)r.source();
      if(r.load)r.load();
      if(r.play)Promise.resolve(r.play()).then(r.resolve,r.reject);
      r.phase='finished';
    }catch(e){r.phase='failed';if(r.reject)r.reject(e)}
  });
  if(mediaPending.size&&mediaPendingTimer===null)
    mediaPendingTimer=w.setTimeout(flushPendingMedia,100);
}
function queuePendingMedia(media,video,kind,execute){
  noteMediaInitialization(kind,video,mediaDecision(video));
  var r=mediaPending.get(media);
  if(r&&(r.video!==video||kind==='source')){
    cancelPendingMedia(media,'Media source changed');r=null;
  }
  if(!r){
    if(mediaPending.size>=MEDIA_PENDING_LIMIT){
      return kind==='play'?Promise.reject(new Error('Media initialization queue is full')):null;
    }
    r={phase:'waiting',video:video,deadline:mediaPendingNow()+MEDIA_PENDING_MS,
      source:null,load:null,play:null,promise:null,resolve:null,reject:null};
    mediaPending.set(media,r);
  }
  if(kind==='play'){
    if(!r.promise){
      r.play=execute;
      r.promise=new Promise(function(resolve,reject){r.resolve=resolve;r.reject=reject});
    }
  }else{
    if(kind==='load')cancelPendingPlay(media,'Media load interrupted playback');
    r[kind]=execute;
  }
  if(mediaDecision(video)==='open')flushPendingMedia();
  else if(mediaPendingTimer===null)mediaPendingTimer=w.setTimeout(flushPendingMedia,100);
  return r.promise;
}
function installMediaGuard(){try{
  installSchoolMediaEvents();
  if(!S.morningStartupPlayEvents&&w.addEventListener){
    S.morningStartupPlayEvents=true;
    w.addEventListener('hashchange',function(event){
      morningStartupDeferred.forEach(function(r,media){
        if(routeVideoId()!==r.video||event&&event.newURL&&event.newURL!==r.href)
          cancelMorningStartupPlay(media,'Media route changed');
      });
    });
    if(w.document&&w.document.addEventListener)w.document.addEventListener('visibilitychange',function(){
      if(w.document.hidden)cancelMorningStartupPlays('Media window hidden');
    });
  }
  var P=w.HTMLMediaElement&&w.HTMLMediaElement.prototype;if(!P)return;
  if(typeof P.play==='function'&&!P.play.__ttMedia){
    var OP=P.play;
    function playAndReport(self,args,video){
      var blocked=morningMediaBlockReason();
      if(blocked){try{self.pause()}catch(e){}return Promise.reject(new Error(blocked))}
      if(routeVideoId()!==video||mediaDecision(video)!=='open')return Promise.reject(new Error(REASON));
      var attempt={video:video,source:mediaSource(self),epoch:S.morningStartupLease&&S.morningStartupLease.epoch};
      morningNativePlay.set(self,attempt);
      var result;try{result=OP.apply(self,args)}catch(e){morningNativePlay.delete(self);return Promise.reject(e)}
      return Promise.resolve(result).then(function(value){
        if(morningNativePlay.get(self)===attempt)morningNativePlay.delete(self);
        if(self.paused!==true&&mediaSource(self)===attempt.source)publishMorningPlaying(video,self);
        return value;
      },function(error){
        if(morningNativePlay.get(self)===attempt)morningNativePlay.delete(self);throw error;
      });
    }
    P.play=function(){
      var video=routeVideoId(),self=this,args=arguments,blocked=morningMediaBlockReason();
      if(blocked){
        if(blocked===MORNING_STARTUP_REASON&&S.morningStartupLease.expected_video_id===''
            &&morningVideoId(video)&&!(w.document&&w.document.hidden)
            &&String(self.tagName||'').toUpperCase()==='VIDEO'&&mediaDecision(video)!=='closed'){
          // Preserve inert source/load initialization; only playback is delayed.
          if(self.paused!==true)try{self.pause()}catch(e){}
          return deferMorningStartupPlay(self,video,function(){return playAndReport(self,args,video)});
        }
        cancelMorningStartupPlay(self,blocked);cancelPendingMedia(self,blocked);try{self.pause()}catch(e){}
        return Promise.reject(new Error(blocked));
      }
      var pending=morningStartupDeferred.get(self);
      if(pending){flushMorningStartupPlays();return pending.promise}
      if(!video)return Promise.resolve();
      var decision=mediaDecision(video);
      if(decision==='pending'||decision==='open'&&mediaPending.has(self))
        return queuePendingMedia(self,video,'play',function(){return playAndReport(self,args,video)});
      if(decision==='open')return playAndReport(self,args,video);
      cancelPendingMedia(self,REASON);mediaBlockedLog();return Promise.reject(new Error(REASON));
    };
    P.play.__ttMedia=1;
  }
  if(typeof P.load==='function'&&!P.load.__ttMedia){
    var OL=P.load;
    P.load=function(){
      cancelMorningStartupPlay(this,'Media load interrupted playback');
      var video=routeVideoId(),decision=video?mediaDecision(video):'open',self=this,args=arguments;
      if(decision==='pending'||decision==='open'&&mediaPending.has(self)){
        queuePendingMedia(self,video,'load',function(){return OL.apply(self,args)});return;
      }
      if(decision!=='open'){cancelPendingMedia(self,REASON);mediaBlockedLog();throw new Error(REASON)}
      return OL.apply(self,args);
    };
    P.load.__ttMedia=1;
  }
  if(typeof P.pause==='function'&&!P.pause.__ttMedia){
    var OPAUSE=P.pause;
    P.pause=function(){
      cancelMorningStartupPlay(this,'Media playback paused');
      cancelPendingPlay(this,'Media playback paused');
      return OPAUSE.apply(this,arguments);
    };
    P.pause.__ttMedia=1;
  }
  var sd=Object.getOwnPropertyDescriptor(P,'src');
  if(sd&&sd.set&&!sd.set.__ttMedia){
    var ws=function(value){
      cancelMorningStartupPlay(this,'Media source changed');
      var video=routeVideoId(),decision=video?mediaDecision(video):'open',src,self=this;
      try{src=String(value).replace(/^[\x00-\x20]+|[\x00-\x20]+$/g,'')}catch(e){mediaBlockedLog();return}
      if(decision!=='open'&&src.toLowerCase().lastIndexOf('blob:',0)===0){
        if(decision==='pending')queuePendingMedia(self,video,'source',function(){sd.set.call(self,value)});
        else{cancelPendingMedia(self,REASON);mediaBlockedLog()}
        return;
      }
      cancelPendingMedia(self,'Media source changed');sd.set.call(self,value);
    };
    ws.__ttMedia=1;
    Object.defineProperty(P,'src',{configurable:sd.configurable!==false,enumerable:!!sd.enumerable,
      get:function(){return sd.get?sd.get.call(this):void 0},set:ws});
  }
  var U=w.URL;
  if(U&&typeof U.createObjectURL==='function'&&!U.createObjectURL.__ttMedia){
    var OU=U.createObjectURL.bind(U);
    var nc=function(value){
      var video=routeVideoId(),decision=video?mediaDecision(video):'open';
      noteMediaInitialization('allocate',video,decision);
      if(decision==='closed'&&value&&typeof value==='object'){mediaBlockedLog();throw new Error(REASON)}
      return OU(value);
    };
    nc.__ttMedia=1;U.createObjectURL=nc;
  }
}catch(e){}}
function guard(){if(!S.installed)return;try{if(typeof w.XMLHttpRequest=='function'){var q=w.XMLHttpRequest.prototype;if(q&&!q.__ttWrap){delete q.__ao;try{installXhr()}catch(e){S.armFail++}}else if(!S.xhrArmed&&S.arm){S.arm()}}if(typeof w.fetch=='function'&&(!w.fetch.__tt||!S.fetch0)){installFetch()}if(w.HTMLMediaElement)installMediaGuard()}catch(e){}}function install(){if(S.installed)return;S.installed=true;bridge();installFetch();installXhr();installMediaGuard();installRouteGate();cfg();renderStatus();watchStatus();watchMorningLifecycle();guard();if(typeof console!='undefined'&&console.log)console.log('[allowed-only] filter active');if(S.ctx)ensure()}
w.__ttAllowedOnly={install:install,state:S,allowed:ok,configure:cfg,filterTree:filter,blockPlayerResponse:player,collectLikedVideoIds:liked,collectAllChannelIds:channels,collectGuideSubscriptionIds:guide,responseChannelId:responseChannelId,rememberChannelPageVideos:rememberChannelPageVideos,collectPlaylistIds:collectPlaylistIds,collectPlaylistVideoIds:collectPlaylistVideoIds,learnAccountPlaylistResponse:learnAccountPlaylistResponse,processApiPayload:payload,collectMusicLibraryVideoIds:collectMusicLibraryVideoIds,collectMusicCollectionVideoIds:collectMusicCollectionVideoIds,collectMusicCollectionBrowseIds:collectMusicCollectionBrowseIds,collectPurchasedVideoIds:collectPurchasedVideoIds,accountLibraries:accountLibraries,startBackgroundEnrichment:startBackgroundEnrichment,continuationTokens:cont,firstVideoId:vid,firstChannelId:authcid,discoverChannelId:cid,videoOwnerChannelId:vcid,addPageStyle:addPageStyle,BLOCK_REASON:REASON,statusText:statusText,renderStatus:renderStatus,statusTick:statusTick,guard:guard,routeVideoId:routeVideoId,mediaDecision:mediaDecision,installMediaGuard:installMediaGuard,verifyDirectVideo:verifyDirectVideo,gateDirectRoute:gateDirectRoute,installRouteGate:installRouteGate,retryDelaySchedule:retryDelaySchedule,bootWithRetry:bootWithRetry,filtapi:filtapi,collectChannelNames:collectChannelNames,tileShouldStay:tileShouldStay,sweepDom:sweepDom,collectMorningProgress:collectMorningProgress,validMorningHistory:validMorningHistory,publishMorningProgress:publishMorningProgress,morningStatusPayload:morningStatusPayload,morningProgressFresh:morningProgressFresh,refreshMorningProgressOnFocus:refreshMorningProgressOnFocus,requestMorningProgress:requestMorningProgress,morningHistoryForRun:morningHistoryForRun,writeMorningProgress:writeMorningProgress,writeMorningPlaying:writeMorningPlaying,publishMorningPlaying:publishMorningPlaying,setSchoolBannerLease:setSchoolBannerLease,setMorningStartupLease:setMorningStartupLease};return w.__ttAllowedOnly;
});

;(function(g){
'use strict';
var A=(typeof module!='undefined'&&module.exports)||g.__ttAllowedOnly;if(!A)return;
var S=A.state,SET='ttAllowedOnlyFeatures',SP='ttSponsorCategories',SPD='ttPlaybackSpeed';
if(!S.features)S.features={adblock:'on',sponsorblock:'on',dearrow:'on'};
S.settingsLoaded=S.settingsLoaded||'off';S.settingsEl=null;S.routeObserver=S.routeObserver||'off';S.routeHistory=null;S.routeMutationObserver=null;
S.sponsorCategories=S.sponsorCategories||defaultSponsorCategories();S.sponsorSegments=S.sponsorSegments||new Map;S.sponsorCache=S.sponsorCache||new Map;S.dearrowCache=S.dearrowCache||new Map;S.videoSpeed=S.videoSpeed||'1';
function store(){try{return g.localStorage||null}catch(e){return null}}
function defaults(){return{adblock:'on',sponsorblock:'on',dearrow:'on'}}
function defaultSponsorCategories(){return{sponsor:'on',intro:'on',outro:'on',interaction:'on',selfpromo:'on',preview:'on',filler:'on',music_offtopic:'on'}}
function rememberAllowedDomNames(x){function r(n){if(!n||typeof n!=='object')return;if(A.firstVideoId(n)&&A.allowed(A.firstVideoId(n),A.firstChannelId(n)))A.collectChannelNames(n);if(Array.isArray(n)){for(var i=0;i<n.length;i++)r(n[i])}else Object.keys(n).forEach(function(k){r(n[k])})}r(x);return S.allowedNames}
function allowedVideoIds(x){var out=[],seen={};function r(n){if(!n||typeof n!=='object')return;var v=A.firstVideoId(n);if(v&&A.allowed(v,A.firstChannelId(n))&&!seen[v]){seen[v]=1;out.push(v)}if(Array.isArray(n)){for(var i=0;i<n.length;i++)r(n[i])}else Object.keys(n).forEach(function(k){r(n[k])})}r(x);return out}
function norm(v,d){return v==='on'||v==='off'?v:d}
function loadSettings(){var d=defaults(),st=store(),x=null;try{x=st&&st.getItem(SET);x=x?JSON.parse(x):null}catch(e){}if(x&&typeof x==='object')Object.keys(d).forEach(function(k){d[k]=norm(x[k],d[k])});S.features=d;S.settingsLoaded='on';try{if(st)st.setItem(SET,JSON.stringify(d))}catch(e){}loadSponsor();loadSpeed();return Object.assign({},d)}
function readySettings(){if(S.settingsLoaded!=='on')loadSettings()}
function featureEnabled(n){if(n==='allowedOnly')return true;if(!/^(adblock|sponsorblock|dearrow)$/.test(n))return false;readySettings();return S.features[n]==='on'}
function saveSettings(p){readySettings();Object.keys(p||{}).forEach(function(k){if(!/^(adblock|sponsorblock|dearrow)$/.test(k))throw Error('unknown feature '+k);if(p[k]!=='on'&&p[k]!=='off')throw Error('invalid feature value '+p[k]);S.features[k]=p[k]});try{var st=store();if(st)st.setItem(SET,JSON.stringify(S.features))}catch(e){}paint();return Object.assign({},S.features)}
function setFeature(n,v){if(n==='allowedOnly'){if(v==='off')throw Error('allowed-only cannot be disabled');return'on'}if(!/^(adblock|sponsorblock|dearrow)$/.test(n))throw Error('unknown feature '+n);if(v!=='on'&&v!=='off')throw Error('invalid feature value '+v);var p={};p[n]=v;saveSettings(p);return v}
function loadSponsor(){var d=defaultSponsorCategories(),st=store(),x=null;try{x=st&&st.getItem(SP);x=x?JSON.parse(x):null}catch(e){}if(x&&typeof x==='object')Object.keys(d).forEach(function(k){d[k]=norm(x[k],d[k])});S.sponsorCategories=d;try{if(st)st.setItem(SP,JSON.stringify(d))}catch(e){}return d}
function setSponsorCategory(n,v){readySettings();if(!Object.prototype.hasOwnProperty.call(S.sponsorCategories,n))throw Error('unknown sponsor category '+n);if(v!=='on'&&v!=='off')throw Error('invalid sponsor category value '+v);S.sponsorCategories[n]=v;if(S.sponsorCache&&S.sponsorCache.clear)S.sponsorCache.clear();if(S.sponsorSegments&&S.sponsorSegments.clear)S.sponsorSegments.clear();try{var st=store();if(st)st.setItem(SP,JSON.stringify(S.sponsorCategories))}catch(e){}paint();return v}
function normalizePlaybackSpeed(v){var n=Number(v);if(String(v)==='1.0001'||n===1.0001)return'1.0001';if(!Number.isFinite(n)||n<.25||n>5||Math.abs(n*4-Math.round(n*4))>1e-7)throw Error('invalid playback speed');return String(Math.round(n*100)/100)}
function loadSpeed(){var v='1',st=store();try{v=normalizePlaybackSpeed(st&&st.getItem(SPD)||'1')}catch(e){}S.videoSpeed=v;return v}
function getPlaybackSpeed(){try{return Number(normalizePlaybackSpeed(S.videoSpeed||'1'))}catch(e){return 1}}
function applyPlaybackSpeed(m){if(!m||typeof m!=='object')return null;var n=getPlaybackSpeed();try{m.playbackRate=n}catch(e){return null}return n}
function applyAllSpeed(){var d=g.document;if(!d||!d.querySelectorAll)return 0;var a=d.querySelectorAll('video'),n=0;for(var i=0;i<a.length;i++)if(applyPlaybackSpeed(a[i])!=null)n++;return n}
function setPlaybackSpeed(v){v=normalizePlaybackSpeed(v);S.videoSpeed=v;try{var st=store();if(st)st.setItem(SPD,v)}catch(e){}applyAllSpeed();paint();return v}
function cycleSpeed(dir){var a=['0.25','0.5','0.75','1','1.25','1.5','1.75','2','2.25','2.5','2.75','3','3.25','3.5','3.75','4','4.25','4.5','4.75','5','1.0001'],v=String(S.videoSpeed||'1'),i=a.indexOf(v);if(i<0)i=3;i=(i+(dir<0?-1:1)+a.length)%a.length;return setPlaybackSpeed(a[i])}
function rm(e){if(!e)return;var p=e.parentNode;if(p&&p.removeChild)try{p.removeChild(e);return}catch(x){}if(p&&Array.isArray(p.children)){var i=p.children.indexOf(e);if(i>=0)p.children.splice(i,1)}e.parentNode=null}
function row(d,attr,name,label,val){var r=d.createElement('button');if(r.setAttribute){r.setAttribute(attr,name);r.setAttribute('tabindex','0');r.setAttribute('class','tt-setting-row')}r.textContent=label+': '+val;return r}
function ensureSettingsUi(){readySettings();var d=g.document;if(!d||!d.createElement)return null;if(S.settingsEl)return S.settingsEl;if(A.addPageStyle)A.addPageStyle(d,'tt-settings-style','#tt-settings-overlay{position:fixed;inset:8%;z-index:2147483647;padding:24px;background:rgba(0,0,0,.96);color:#fff;overflow:auto}#tt-settings-overlay .tt-setting-row{display:block;width:100%;margin:6px 0;padding:12px;background:#202124;color:#fff;border:2px solid #666;font:600 20px Arial;text-align:left}');var e=d.createElement('div');if(e.setAttribute){e.setAttribute('id','tt-settings-overlay');e.setAttribute('role','dialog')}var h=d.createElement('div');h.textContent='TizenTube Settings';e.appendChild(h);[['adblock','Ad Blocker'],['sponsorblock','SponsorBlock'],['dearrow','DeArrow']].forEach(function(a){var r=row(d,'data-feature',a[0],a[1],S.features[a[0]]);r.onclick=function(){setFeature(a[0],S.features[a[0]]==='on'?'off':'on')};e.appendChild(r)});var sr=row(d,'data-setting','speed','Playback speed',S.videoSpeed+'x');sr.onclick=function(){cycleSpeed(1)};e.appendChild(sr);Object.keys(S.sponsorCategories).forEach(function(k){var r=row(d,'data-sponsor-category',k,'SponsorBlock '+k,S.sponsorCategories[k]);r.onclick=function(){setSponsorCategory(k,S.sponsorCategories[k]==='on'?'off':'on')};e.appendChild(r)});S.settingsEl=e;var root=d.body||d.documentElement;if(root&&root.appendChild)root.appendChild(e);S.settingsIndex=0;paint();return e}
function rows(){var e=S.settingsEl;if(!e||!e.children)return[];return Array.prototype.filter.call(e.children,function(r){return r.getAttribute&&(r.getAttribute('data-feature')||r.getAttribute('data-setting')||r.getAttribute('data-sponsor-category'))})}
function paint(){if(!S.settingsEl)return;var a=rows();if(S.settingsIndex>=a.length)S.settingsIndex=0;for(var i=0;i<a.length;i++){var r=a[i],f=r.getAttribute('data-feature'),c=r.getAttribute('data-sponsor-category'),q=r.getAttribute('data-setting'),label=f==='adblock'?'Ad Blocker':f==='sponsorblock'?'SponsorBlock':f==='dearrow'?'DeArrow':c?'SponsorBlock '+c:'Playback speed',val=f?S.features[f]:c?S.sponsorCategories[c]:S.videoSpeed+'x';r.textContent=(i===S.settingsIndex?'Ã¢â€“Â¶ ':'  ')+label+': '+val}}
function openSettings(){return ensureSettingsUi()?'open':'closed'}
function closeSettings(){rm(S.settingsEl);S.settingsEl=null;return'closed'}
function key(ev){var c=ev&&ev.keyCode||0,k=ev&&ev.key||'';if(!S.settingsEl){if(c===82||c===176||c===403||k==='F1'){openSettings();if(ev.preventDefault)ev.preventDefault()}else if(c===406||c===191||k==='F4'){openSettings();var a=rows();for(var i=0;i<a.length;i++)if(a[i].getAttribute('data-setting')==='speed')S.settingsIndex=i;paint();if(ev.preventDefault)ev.preventDefault()}return}var a=rows();if(c===4||c===27||k==='Escape'||k==='Backspace'){closeSettings();return}if(c===38||k==='ArrowUp')S.settingsIndex=(S.settingsIndex+a.length-1)%a.length;else if(c===40||k==='ArrowDown')S.settingsIndex=(S.settingsIndex+1)%a.length;else if(c===13||k==='Enter'){var r=a[S.settingsIndex];if(r&&r.onclick)r.onclick()}else if(c===37||k==='ArrowLeft'||c===39||k==='ArrowRight'){var r=a[S.settingsIndex];if(r&&r.getAttribute('data-setting')==='speed')cycleSpeed(c===37||k==='ArrowLeft'?-1:1);else return}else return;if(ev.preventDefault)ev.preventDefault();if(ev.stopPropagation)ev.stopPropagation();paint()}
function adNode(x){if(!x||typeof x!=='object')return false;var ks=Object.keys(x);for(var i=0;i<ks.length;i++)if(/^(adSlotRenderer|adPlacementRenderer|promotedVideoRenderer|displayAdRenderer|inFeedAdLayoutRenderer)$/.test(ks[i]))return true;if(ks.length===1&&/^(content|richItemRenderer)$/.test(ks[0]))return adNode(x[ks[0]]);return false}
function sanitizeAdPayload(x){if(!featureEnabled('adblock')||!x||typeof x!=='object')return x;function clean(n){if(Array.isArray(n))return n.filter(function(v){return!adNode(v)}).map(clean);if(!n||typeof n!=='object')return n;['adPlacements','playerAds','adSlots'].forEach(function(k){delete n[k]});Object.keys(n).forEach(function(k){if(/^(adSlotRenderer|adPlacementRenderer|promotedVideoRenderer|displayAdRenderer|inFeedAdLayoutRenderer)$/.test(k))delete n[k];else n[k]=clean(n[k])});return n}return clean(x)}
function shouldBlockAdRequest(u){if(!featureEnabled('adblock')||!u)return false;var h=(u.hostname||'').toLowerCase(),p=(u.pathname||'').toLowerCase();return /doubleclick\.net$|googleadservices\.com$|googlesyndication\.com$/.test(h)||/\/(pagead|ptracking)(\/|$)/.test(p)}
function routeVideoId(){return A.routeVideoId()}
function isWatchRoute(){try{if(routeVideoId())return true;var l=g.location;if(!l)return false;return /watch/.test(new URL(l.href||'https://www.youtube.com/').pathname+(l.hash||''))}catch(e){return false}}
function isSettingsRoute(){try{var l=g.location;if(!l)return false;var u=new URL(l.href||'https://www.youtube.com/');return /(?:^|[\/#])settings(?:[\/?#]|$)|preferences/i.test(u.pathname+(u.hash||''))}catch(e){return false}}
var oldSweep=A.sweepDom;
function elementVideoId(e){if(!e)return null;function from(n){if(!n)return null;var a=[];if(n.getAttribute){a.push(n.getAttribute('data-video-id'),n.getAttribute('video-id'),n.getAttribute('data-videoid'),n.getAttribute('href'))}if(typeof n.href=='string')a.push(n.href);for(var i=0;i<a.length;i++){var q=a[i];if(typeof q!='string'||!q)continue;if(/^[\w-]{6,}$/.test(q)&&q.indexOf('/')<0)return q;try{var u=new URL(q,g.location&&g.location.href||'https://www.youtube.com/'),v=u.searchParams.get('v'),m=/\/watch\/([\w-]+)/.exec(u.pathname);if(v)return v;if(m)return m[1]}catch(x){}}return null}var v=from(e);if(v)return v;if(e.querySelectorAll){try{var a=e.querySelectorAll('a[href], [data-video-id], [video-id], [data-videoid]')||[];for(var i=0;i<a.length;i++){v=from(a[i]);if(v)return v}}catch(x){}}return null}
g.__ttElementVideoId=elementVideoId;
function enhancedSweep(){var n=oldSweep?oldSweep():0,d=g.document,seen=[];if(!d||!d.querySelectorAll)return n;function drop(e,force){if(!e||seen.indexOf(e)>=0)return;seen.push(e);if(e.getAttribute&&e.getAttribute('role')==='button')return;var v=elementVideoId(e);if(!force&&v){if(A.allowed(v))return;rm(e);n++;S.stripped++;return}var t=e.innerText||e.textContent||'',stay=A.tileShouldStay?A.tileShouldStay(t,S.allowedNames):false;if(S.loggedIn!==false&&!force&&stay)return;rm(e);n++;S.stripped++}var sels=['ytlr-watch-next-renderer','ytlr-end-screen-video-renderer','ytlr-compact-video-renderer','ytlr-autoplay-renderer'];if(featureEnabled('adblock'))sels=sels.concat(['ytlr-promoted-video-renderer','ytlr-ad-slot-renderer']);sels.forEach(function(s){var a=[];try{a=d.querySelectorAll(s)||[]}catch(e){}for(var i=0;i<a.length;i++)drop(a[i],/promoted|ad-slot/.test(s))});return n}
function installRouteObserver(){var h=g.history,d=g.document;if(S.routeObserver==='on'&&S.routeHistory===h)return'on';S.routeHistory=h||null;if(h)['pushState','replaceState'].forEach(function(k){var f=h[k];if(typeof f!=='function'||f.__ttRoute)return;var q=function(){var r=f.apply(this,arguments);onRouteChanged();return r};q.__ttRoute=1;h[k]=q});if(g.addEventListener){g.addEventListener('popstate',onRouteChanged);g.addEventListener('hashchange',onRouteChanged)}if(g.MutationObserver&&d&&(d.body||d.documentElement)){S.routeMutationObserver=new g.MutationObserver(function(){if(isWatchRoute())enhancedSweep()});S.routeMutationObserver.observe(d.body||d.documentElement,{childList:true,subtree:true})}S.routeObserver='on';onRouteChanged();return'on'}
function sponsorBlockUrl(v){var cats=Object.keys(S.sponsorCategories).filter(function(k){return S.sponsorCategories[k]==='on'});return'https://sponsor.ajay.app/api/skipSegments?videoID='+encodeURIComponent(v)+'&categories='+encodeURIComponent(JSON.stringify(cats))}
function parseSponsorSegments(x,cats){cats=cats||S.sponsorCategories;if(x&&Array.isArray(x.segments))x=x.segments;if(Array.isArray(x)&&x[0]&&x[0].videoID&&Array.isArray(x[0].segments))x=x[0].segments;if(!Array.isArray(x))return[];return x.filter(function(q){var a=q&&q.segment;return Array.isArray(a)&&typeof a[0]==='number'&&typeof a[1]==='number'&&a[1]>a[0]&&(!q.actionType||q.actionType==='skip')&&cats[q.category]==='on'}).map(function(q){return{start:q.segment[0],end:q.segment[1],category:q.category,UUID:q.UUID||null}}).sort(function(a,b){return a.start-b.start})}
async function fetchSponsor(v){if(!featureEnabled('sponsorblock')||!v)return[];if(S.sponsorCache.has(v))return S.sponsorCache.get(v);var f=S.fetch0||g.fetch;if(!f)return[];try{var r=await f(sponsorBlockUrl(v));if(!r.ok)return[];var a=parseSponsorSegments(await r.json());S.sponsorCache.set(v,a);S.sponsorSegments.set(v,a);return a}catch(e){return[]}}
function maybeSkipSponsor(v,m){if(!featureEnabled('sponsorblock')||!m)return null;var a=S.sponsorSegments.get(v)||[],t=Number(m.currentTime);for(var i=0;i<a.length;i++)if(t>=a[i].start&&t<a[i].end){m.currentTime=a[i].end;return a[i].end}return null}
function best(a,field){if(!Array.isArray(a))return null;var v=a.filter(function(x){return x&&(field==='title'?typeof x.title==='string':typeof x.timestamp==='number')&&(x.locked===true||Number(x.votes||0)>=0)});if(!v.length)return null;var l=v.filter(function(x){return x.locked===true});v=l.length?l:v;v.sort(function(a,b){return Number(b.votes||0)-Number(a.votes||0)});return v[0]}
function selectDeArrowTitle(x){var q=best(x&&x.titles,'title');return q?q.title:null}
function selectDeArrowThumbnail(x){return best(x&&x.thumbnails,'thumbnail')}
function deArrowThumbnailUrl(v,t){if(!t||typeof t.timestamp!=='number')return null;return'https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID='+encodeURIComponent(v)+'&time='+t.timestamp}
function uniqPush(a,e){if(e&&a.indexOf(e)<0)a.push(e)}
function deArrowRoot(e){var r=e;for(var i=0;i<7&&r;i++,r=r.parentNode){var t=String(r.tagName||'').toLowerCase();if(/^ytlr-(?:tile|compact-video|watch-next|end-screen-video|autoplay)-renderer$/.test(t))return r}return e}
function applyDeArrowDom(v,b){if(!featureEnabled('dearrow')||!b)return 0;var d=g.document;if(!d||!d.querySelectorAll)return 0;var cand=[],roots=[],sels=['[data-video-id="'+v+'"]','[video-id="'+v+'"]','[data-videoid="'+v+'"]','a[href*="watch?v='+v+'"]','a[href*="watch/'+v+'"]'];for(var si=0;si<sels.length;si++){try{var q=d.querySelectorAll(sels[si])||[];for(var qi=0;qi<q.length;qi++)uniqPush(cand,q[qi])}catch(e){}}for(var ci=0;ci<cand.length;ci++)uniqPush(roots,deArrowRoot(cand[ci]));var t=selectDeArrowTitle(b),th=selectDeArrowThumbnail(b),changed=[];function titleEl(e){if(!t||!e||changed.indexOf(e)>=0)return;try{e.textContent=t;changed.push(e)}catch(x){}}function imageEl(e){if(!th||!e||changed.indexOf(e)>=0)return;var u=deArrowThumbnailUrl(v,th);try{if(e.setAttribute)e.setAttribute('src',u);else e.src=u;changed.push(e)}catch(x){}}for(var ri=0;ri<roots.length;ri++){var r=roots[ri],tag=String(r.tagName||'').toUpperCase();if(tag==='IMG'||('src'in r&&tag!=='A')){imageEl(r);continue}var used=false;if(r.querySelectorAll){try{var imgs=r.querySelectorAll('img')||[];if(imgs.length){imageEl(imgs[0]);used=true}}catch(e){}try{var titles=r.querySelectorAll('[idomkey*="title"],[class*="title"],ytlr-tile-metadata-renderer,ytlr-video-title-renderer')||[];if(titles.length){titleEl(titles[0]);used=true}}catch(e){}}if(!used&&tag!=='A'&&typeof r.textContent==='string')titleEl(r)}return changed.length}
async function fetchBranding(v){if(!featureEnabled('dearrow')||!v)return null;if(S.dearrowCache.has(v))return S.dearrowCache.get(v);var f=S.fetch0||g.fetch;if(!f)return null;try{var r=await f('https://sponsor.ajay.app/api/branding?videoID='+encodeURIComponent(v));if(!r.ok)return null;var x=await r.json();S.dearrowCache.set(v,x);return x}catch(e){return null}}
function queueDeArrowFromPayload(x){if(!featureEnabled('dearrow')||!g.document)return;var ids=allowedVideoIds(x).slice(0,80);ids.forEach(function(v){fetchBranding(v).then(function(b){if(!b)return;var run=function(){applyDeArrowDom(v,b)};if(typeof g.setTimeout==='function'){g.setTimeout(run,0);g.setTimeout(run,350);g.setTimeout(run,1200)}else run()})})}
function refresh(v){if(!v||!S.ready||!A.allowed(v))return;var d=g.document,m=d&&d.querySelector&&d.querySelector('video');if(m){applyPlaybackSpeed(m);m.__ttSponsorVideo=v;if(m.addEventListener&&!m.__ttSponsorHandler){m.__ttSponsorHandler=function(){maybeSkipSponsor(m.__ttSponsorVideo,m)};m.addEventListener('timeupdate',m.__ttSponsorHandler)}fetchSponsor(v).then(function(){maybeSkipSponsor(v,m)})}fetchBranding(v).then(function(b){if(b)applyDeArrowDom(v,b)})}
function onRouteChanged(){var n=enhancedSweep();if(isSettingsRoute()){if(S.settingsRouteShown!=='on'){S.settingsRouteShown='on';openSettings()}}else S.settingsRouteShown='off';refresh(routeVideoId());return n}
function installEnhancements(){loadSettings();installRouteObserver();var d=g.document;if(d&&d.addEventListener&&S.settingsKeys!=='on'){d.addEventListener('keydown',key,true);d.addEventListener('canplay',function(e){applyPlaybackSpeed(e.target)},true);S.settingsKeys='on'}applyAllSpeed()}
g.__ttShouldBlockAdRequest=shouldBlockAdRequest;
g.__ttEnhancePayload=function(u,x){rememberAllowedDomNames(x);queueDeArrowFromPayload(x);return sanitizeAdPayload(x)};
var oldFilter=A.filterTree;A.filterTree=function(x){var y=oldFilter(x);rememberAllowedDomNames(y);return sanitizeAdPayload(y)};A.sweepDom=enhancedSweep;A.shouldBlockAdRequest=shouldBlockAdRequest;A.sanitizeAdPayload=sanitizeAdPayload;A.defaultFeatures=defaults;A.loadSettings=loadSettings;A.saveSettings=saveSettings;A.featureEnabled=featureEnabled;A.setFeature=setFeature;A.ensureSettingsUi=ensureSettingsUi;A.openSettings=openSettings;A.closeSettings=closeSettings;A.defaultSponsorCategories=defaultSponsorCategories;A.setSponsorCategory=setSponsorCategory;A.parseSponsorSegments=parseSponsorSegments;A.sponsorBlockUrl=sponsorBlockUrl;A.fetchSponsorSegments=fetchSponsor;A.maybeSkipSponsor=maybeSkipSponsor;A.selectDeArrowTitle=selectDeArrowTitle;A.selectDeArrowThumbnail=selectDeArrowThumbnail;A.deArrowThumbnailUrl=deArrowThumbnailUrl;A.applyDeArrowDom=applyDeArrowDom;A.fetchBranding=fetchBranding;A.normalizePlaybackSpeed=normalizePlaybackSpeed;A.setPlaybackSpeed=setPlaybackSpeed;A.getPlaybackSpeed=getPlaybackSpeed;A.applyPlaybackSpeed=applyPlaybackSpeed;A.isWatchRoute=isWatchRoute;A.isSettingsRoute=isSettingsRoute;A.installRouteObserver=installRouteObserver;A.onRouteChanged=onRouteChanged;A.elementVideoId=elementVideoId;A.refreshEnhancements=refresh;
var oldInstall=A.install;A.install=function(){var r=oldInstall.apply(this,arguments);installEnhancements();return r};
if(typeof module!='undefined'&&module.exports)module.exports=A;
if(g&&g.document&&g.location)installEnhancements();
})(typeof window!='undefined'?window:globalThis);
