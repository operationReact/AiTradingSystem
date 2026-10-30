import { createReadStream, existsSync, statSync } from 'node:fs';
import { createServer, request as httpRequest } from 'node:http';
import { dirname, extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const toolDir = dirname(fileURLToPath(import.meta.url));
const root = normalize(join(toolDir, '..', 'build', 'web'));
const port = Number(process.env.PORT ?? 4173);
const backend = new URL(process.env.BACKEND_URL ?? 'http://127.0.0.1:8080');
const requiredFiles = ['index.html', 'main.dart.js'];
for (const file of requiredFiles) {
  const path = join(root, file);
  if (!existsSync(path)) throw new Error(`Flutter web build artifact missing: ${path}`);
}

const mime = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.wasm': 'application/wasm',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.ttf': 'font/ttf',
};

function proxy(req, res) {
  const target = new URL(req.url, backend);
  console.log(`${req.method} ${req.url} -> ${target}`);
  const upstream = httpRequest(target, {
    method: req.method,
    headers: { ...req.headers, host: backend.host },
  }, (upstreamResponse) => {
    res.writeHead(upstreamResponse.statusCode ?? 502, upstreamResponse.headers);
    upstreamResponse.pipe(res);
  });
  upstream.on('error', (error) => {
    console.error(`proxy failure for ${req.url}: ${error.stack ?? error}`);
    res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' });
    res.end(`proxy failure: ${error.message}`);
  });
  req.pipe(upstream);
}

function serve(req, res) {
  const pathname = decodeURIComponent(new URL(req.url, `http://${req.headers.host}`).pathname);
  if (pathname.startsWith('/api/') || pathname.startsWith('/actuator/')) {
    proxy(req, res);
    return;
  }
  const requested = pathname === '/' ? '/index.html' : pathname;
  const relative = normalize(requested).replace(/^([/\\])+/, '');
  let file = join(root, relative);
  let fallback = false;
  if (!file.startsWith(root) || !existsSync(file) || !statSync(file).isFile()) {
    file = join(root, 'index.html');
    fallback = true;
  }
  console.log(`${req.method} ${pathname} -> ${file}${fallback ? ' (SPA fallback)' : ''}`);
  res.writeHead(200, {
    'content-type': mime[extname(file)] ?? 'application/octet-stream',
    'cache-control': 'no-store',
  });
  createReadStream(file).pipe(res);
}

createServer(serve).listen(port, '127.0.0.1', () => {
  console.log(`Flutter web root: ${root}`);
  console.log(`Flutter web runtime listening on http://127.0.0.1:${port}, proxying to ${backend}`);
});
