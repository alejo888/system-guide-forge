const assert = require('node:assert/strict');

const backendUrl = (process.env.SGF_BACKEND_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const fixtureUrl = (process.env.SGF_FIXTURE_URL || 'http://127.0.0.1:4173').replace(/\/$/, '');
const username = process.env.FIXTURE_USERNAME || 'fixture-user';
const password = process.env.FIXTURE_PASSWORD || 'fixture-password';

async function jsonRequest(path, options = {}) {
  const response = await fetch(`${backendUrl}${path}`, {
    headers: { 'content-type': 'application/json', ...(options.headers || {}) },
    ...options
  });
  const body = await response.text();
  assert.ok(response.ok, `${options.method || 'GET'} ${path} failed: ${response.status} ${body}`);
  return body ? JSON.parse(body) : null;
}

async function main() {
  const project = await jsonRequest('/api/projects', {
    method: 'POST', body: JSON.stringify({ name: `E2E fixture ${Date.now()}` })
  });
  const application = await jsonRequest(`/api/projects/${project.id}/applications`, {
    method: 'POST',
    body: JSON.stringify({
      name: 'Deterministic fixture', baseUrl: fixtureUrl,
      loginUrl: `${fixtureUrl}/login.html`, username, password
    })
  });
  const access = await jsonRequest(`/api/applications/${application.id}/test-access`, { method: 'POST' });
  assert.equal(access.authenticated, true, JSON.stringify(access));

  const started = await jsonRequest(`/api/applications/${application.id}/analyses`, { method: 'POST' });
  let analysis = started;
  for (let attempt = 0; attempt < 20 && analysis.status === 'RUNNING'; attempt++) {
    await new Promise((resolve) => setTimeout(resolve, 250));
    analysis = await jsonRequest(`/api/analyses/${started.id}`);
  }
  assert.equal(analysis.status, 'COMPLETED', JSON.stringify(analysis));
  const pages = await jsonRequest(`/api/analyses/${started.id}/pages`);
  assert.ok(pages.length > 0, 'analysis returned no pages');
  const elements = await jsonRequest(`/api/pages/${pages[0].id}/elements`);
  assert.ok(elements.length > 0, 'analysis returned no elements');
  assert.ok(elements.some((element) => element.actionClassification === 'SAFE'), 'analysis returned no SAFE element');
  assert.ok(elements.some((element) => element.actionClassification === 'MUTATING'), 'analysis returned no MUTATING element');
  assert.ok(elements.some((element) => element.actionClassification === 'UNKNOWN'), 'analysis returned no UNKNOWN element');
  const screenshotResponse = await fetch(`${backendUrl}/api/pages/${pages[0].id}/screenshot`);
  assert.equal(screenshotResponse.status, 200);
  assert.equal(screenshotResponse.headers.get('content-type'), 'image/png');
  const screenshot = Buffer.from(await screenshotResponse.arrayBuffer());
  assert.deepEqual([...screenshot.subarray(0, 8)], [137, 80, 78, 71, 13, 10, 26, 10]);
  console.log(`E2E passed: authenticated=true, status=COMPLETED, pages=${pages.length}, elements=${elements.length}, screenshot=PNG`);
}

main().catch((error) => {
  console.error(`E2E failed. Ensure backend and fixture are running: ${error.message}`);
  process.exitCode = 1;
});
