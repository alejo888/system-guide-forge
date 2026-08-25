const test = require('node:test');
const assert = require('node:assert/strict');
const { createServer } = require('../server');

test('fixture protects dashboard and supports login/logout', async (t) => {
  const server = createServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => server.close());

  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  const dashboard = await fetch(`${baseUrl}/dashboard.html`, { redirect: 'manual' });
  assert.equal(dashboard.status, 302);
  assert.equal(dashboard.headers.get('location'), '/login.html');

  const invalidLogin = await fetch(`${baseUrl}/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username: 'fixture-user', password: 'wrong-password' })
  });
  assert.equal(invalidLogin.status, 401);

  const login = await fetch(`${baseUrl}/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username: 'fixture-user', password: 'fixture-password' }),
    redirect: 'manual'
  });
  assert.equal(login.status, 302);
  assert.equal(login.headers.get('location'), '/dashboard.html');
  const cookie = login.headers.get('set-cookie');
  assert.ok(cookie);

  const authenticated = await fetch(`${baseUrl}/dashboard.html`, {
    headers: { cookie: cookie.split(';', 1)[0] }
  });
  assert.equal(authenticated.status, 200);
  assert.match(await authenticated.text(), /SystemGuideForge Fixture Dashboard/);

  const logout = await fetch(`${baseUrl}/logout`, {
    method: 'POST',
    headers: { cookie: cookie.split(';', 1)[0] },
    redirect: 'manual'
  });
  assert.equal(logout.status, 302);

  const afterLogout = await fetch(`${baseUrl}/dashboard.html`, {
    headers: { cookie: cookie.split(';', 1)[0] },
    redirect: 'manual'
  });
  assert.equal(afterLogout.status, 302);
});
