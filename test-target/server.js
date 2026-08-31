const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { URL } = require('node:url');

const publicDirectory = path.join(__dirname, 'public');
const sessions = new Set();
const fixtureCredentials = {
  username: process.env.FIXTURE_USERNAME || 'fixture-user',
  password: process.env.FIXTURE_PASSWORD || 'fixture-password'
};

function sendRedirect(response, location) {
  response.writeHead(302, { location });
  response.end();
}

function isAuthenticated(request) {
  const cookies = request.headers.cookie || '';
  const sessionCookie = cookies.split(';').map((item) => item.trim())
    .find((item) => item.startsWith('sgf_fixture_session='));
  return sessionCookie ? sessions.has(sessionCookie.slice('sgf_fixture_session='.length)) : false;
}

function serveFile(response, fileName) {
  const filePath = path.join(publicDirectory, fileName);
  fs.readFile(filePath, (error, content) => {
    if (error) {
      response.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
      response.end('Not found');
      return;
    }
    const contentType = fileName.endsWith('.html') ? 'text/html; charset=utf-8' : 'text/plain; charset=utf-8';
    response.writeHead(200, { 'content-type': contentType, 'cache-control': 'no-store' });
    response.end(content);
  });
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    let body = '';
    request.on('data', (chunk) => { body += chunk; });
    request.on('end', () => resolve(new URLSearchParams(body)));
    request.on('error', reject);
  });
}

function createServer() {
  return http.createServer(async (request, response) => {
    const requestUrl = new URL(request.url, 'http://fixture.local');

    if (request.method === 'GET' && requestUrl.pathname === '/') {
      sendRedirect(response, '/login.html');
      return;
    }
    if (request.method === 'GET' && (requestUrl.pathname === '/dashboard.html' || requestUrl.pathname === '/reports.html')) {
      if (!isAuthenticated(request)) {
        sendRedirect(response, '/login.html');
        return;
      }
      serveFile(response, requestUrl.pathname.slice(1));
      return;
    }
    if (request.method === 'GET' && requestUrl.pathname === '/login.html') {
      serveFile(response, 'login.html');
      return;
    }
    if (request.method === 'POST' && requestUrl.pathname === '/login') {
      const form = await readBody(request);
      if (form.get('username') !== fixtureCredentials.username || form.get('password') !== fixtureCredentials.password) {
        response.writeHead(401, { 'content-type': 'text/html; charset=utf-8' });
        response.end('<p>Invalid fixture credentials.</p><a href="/login.html">Try again</a>');
        return;
      }
      const sessionId = crypto.randomBytes(18).toString('hex');
      sessions.add(sessionId);
      response.writeHead(302, {
        location: '/dashboard.html',
        'set-cookie': `sgf_fixture_session=${sessionId}; HttpOnly; SameSite=Strict; Path=/`
      });
      response.end();
      return;
    }
    if (request.method === 'POST' && requestUrl.pathname === '/logout') {
      const cookies = request.headers.cookie || '';
      const sessionCookie = cookies.split(';').map((item) => item.trim())
        .find((item) => item.startsWith('sgf_fixture_session='));
      if (sessionCookie) sessions.delete(sessionCookie.slice('sgf_fixture_session='.length));
      response.writeHead(302, {
        location: '/login.html',
        'set-cookie': 'sgf_fixture_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0'
      });
      response.end();
      return;
    }

    response.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
    response.end('Not found');
  });
}

if (require.main === module) {
  const port = Number(process.env.PORT || 4173);
  createServer().listen(port, '127.0.0.1', () => {
    console.log(`SystemGuideForge fixture listening at http://127.0.0.1:${port}`);
  });
}

module.exports = { createServer };
