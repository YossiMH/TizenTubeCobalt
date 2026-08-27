'use strict';

const assert = require('assert');
const mod = require('../x.js');
const S = mod.state;

void (async () => {
  assert.strictEqual(typeof mod.selectDeArrowTitle, 'function');
  assert.strictEqual(typeof mod.selectDeArrowThumbnail, 'function');
  assert.strictEqual(typeof mod.deArrowThumbnailUrl, 'function');
  assert.strictEqual(typeof mod.applyDeArrowDom, 'function');

  S.features = mod.defaultFeatures();

  assert.strictEqual(mod.selectDeArrowTitle({
    titles: [
      { title: 'Low vote title', votes: 2 },
      { title: 'Community title', votes: 9 },
      { title: 'Locked title', votes: 1, locked: true },
    ]
  }), 'Locked title', 'a locked DeArrow title must outrank ordinary voting');

  assert.strictEqual(mod.selectDeArrowTitle({
    titles: [
      { title: 'Low vote title', votes: 2 },
      { title: 'High vote title', votes: 9 },
    ]
  }), 'High vote title', 'highest-voted valid title must be selected');
  assert.strictEqual(mod.selectDeArrowTitle({ titles: [] }), null,
    'missing branding must fall back without inventing a title');

  const thumb = mod.selectDeArrowThumbnail({
    thumbnails: [
      { timestamp: 3.5, votes: 1 },
      { timestamp: 9.25, votes: 7 },
    ]
  });
  assert.strictEqual(thumb.timestamp, 9.25);
  assert.strictEqual(
    mod.deArrowThumbnailUrl('abc-123', thumb),
    'https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=abc-123&time=9.25'
  );

  const title = {
    textContent: 'Original clickbait',
    getAttribute(name) { return name === 'data-video-id' ? 'abc-123' : null; },
  };
  const image = {
    src: 'https://i.ytimg.com/original.jpg',
    getAttribute(name) { return name === 'data-video-id' ? 'abc-123' : null; },
    setAttribute(name, value) { if (name === 'src') this.src = value; },
  };
  const savedDocument = globalThis.document;
  globalThis.document = {
    querySelectorAll(selector) {
      if (selector === '[data-video-id="abc-123"]') return [title, image];
      return [];
    },
  };
  try {
    assert.strictEqual(mod.applyDeArrowDom('abc-123', {
      titles: [{title:'Clear community title', votes:5}],
      thumbnails: [{timestamp:12, votes:3}],
    }), 2, 'DeArrow must apply title and thumbnail branding to matching content');
    assert.strictEqual(title.textContent, 'Clear community title');
    assert.strictEqual(image.src,
      'https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=abc-123&time=12');

    mod.setFeature('dearrow', 'off');
    title.textContent = 'Original again';
    assert.strictEqual(mod.applyDeArrowDom('abc-123', {
      titles: [{title:'Should not apply', votes:50}],
    }), 0, 'disabled DeArrow must not mutate titles');
    assert.strictEqual(title.textContent, 'Original again');
  } finally {
    globalThis.document = savedDocument;
    mod.setFeature('dearrow', 'on');
  }

  const link = {
    tagName: 'A',
    textContent: '',
    parentNode: null,
    getAttribute(name) {
      if (name === 'href') return '/watch?v=abc-456';
      return null;
    },
    querySelectorAll() { return []; },
  };
  const realTitle = {
    tagName: 'SPAN',
    textContent: 'Real DOM original',
    parentNode: null,
    getAttribute() { return null; },
  };
  const realImage = {
    tagName: 'IMG',
    src: 'https://i.ytimg.com/real-original.jpg',
    parentNode: null,
    getAttribute(name) { return name === 'src' ? this.src : null; },
    setAttribute(name, value) { if (name === 'src') this.src = value; },
  };
  const tile = {
    tagName: 'YTLR-TILE-RENDERER',
    textContent: 'Real DOM original',
    parentNode: null,
    getAttribute() { return null; },
    querySelectorAll(selector) {
      if (selector === 'img') return [realImage];
      if (selector.indexOf('title') >= 0 || selector.indexOf('metadata') >= 0) return [realTitle];
      return [];
    },
  };
  link.parentNode = tile;
  const savedDocument2 = globalThis.document;
  globalThis.document = {
    querySelectorAll(selector) {
      if (selector.indexOf('abc-456') >= 0) return [link];
      return [];
    },
  };
  try {
    assert.strictEqual(mod.applyDeArrowDom('abc-456', {
      titles: [{title:'Real DOM community title', votes:7}],
      thumbnails: [{timestamp:17, votes:4}],
    }), 2, 'DeArrow must work from ordinary YouTube watch links inside real tile containers');
    assert.strictEqual(realTitle.textContent, 'Real DOM community title');
    assert.strictEqual(realImage.src,
      'https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=abc-456&time=17');
  } finally {
    globalThis.document = savedDocument2;
  }

  S.liked.clear();
  S.liked.add('allowed1');
  S.subs.clear();
  S.loggedIn = true;
  S.ready = true;
  const filtered = mod.filterTree({contents:[
    {videoRenderer:{videoId:'allowed1'}},
    {videoRenderer:{videoId:'blocked1'}}
  ]});
  assert.deepStrictEqual(filtered.contents.map(x => x.videoRenderer.videoId), ['allowed1'],
    'DeArrow support must not weaken allow-only filtering');

  console.log('All TizenTube DeArrow tests passed.');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
