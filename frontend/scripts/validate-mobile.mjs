import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const root = new URL('../', import.meta.url);
const read = (file) => readFileSync(new URL(file, root), 'utf8');
const manifest = JSON.parse(read('public/manifest.webmanifest'));
const html = read('index.html');

assert.equal(manifest.display, 'standalone', 'Mobile installation must use standalone display');
assert.equal(manifest.start_url, '/', 'App must launch at the root route');
assert.equal(manifest.scope, '/', 'Navigation scope must include the app routes');
assert.equal(manifest.name, 'SignalForge AI Trading Lab');
assert.match(manifest.theme_color, /^#[0-9a-fA-F]{6}$/);
assert.match(manifest.background_color, /^#[0-9a-fA-F]{6}$/);
assert.ok(Array.isArray(manifest.icons) && manifest.icons.length > 0, 'At least one icon is required');
for (const icon of manifest.icons) {
  assert.ok(icon.src.startsWith('/') && !icon.src.includes('..'), 'Icon must be a local absolute path');
  assert.ok(existsSync(new URL('public' + icon.src, root)), 'Manifest icon file must exist: ' + icon.src);
}
assert.match(html, /name="viewport"[^>]*viewport-fit=cover/, 'Viewport must support mobile safe areas');
assert.match(html, /rel="manifest" href="\/manifest\.webmanifest"/, 'HTML must link the manifest');
assert.match(html, /name="theme-color"/, 'Theme color must be defined');
const shell = read('src/components/AppShell.jsx');
assert.match(shell, /aria-label="Mobile primary"/, 'Mobile bottom navigation must be accessible');
assert.match(shell, /env\(safe-area-inset-bottom\)/, 'Mobile navigation must respect safe-area inset');
console.log('Mobile metadata and navigation validation passed');
