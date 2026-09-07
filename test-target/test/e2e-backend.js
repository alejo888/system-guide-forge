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

function manualContent(document) {
  return document.sections.map((section) => section.content).join('\n');
}

function assertCleanManual(document, elements) {
  const content = manualContent(document);
  for (const selector of elements.map((element) => element.selector).filter(Boolean)) {
    assert.ok(!content.includes(selector), `manual leaked selector ${selector}`);
  }
  assert.doesNotMatch(content, /\b(?:SAFE|UNKNOWN|MUTATING)\b/, 'manual leaked a classification label');
  for (const sensitiveValue of [username, password, 'fixture-sensitive-value']) {
    assert.ok(!content.includes(sensitiveValue), 'manual leaked a fixture credential or sensitive value');
  }
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
  const traversedPaths = new Set(pages.map((page) => new URL(page.url).pathname));
  assert.deepEqual([...traversedPaths].sort(), ['/dashboard.html', '/reports.html']);
  for (const unsafeSentinelPath of ['/login.html', '/logout']) {
    assert.ok(!traversedPaths.has(unsafeSentinelPath), `analysis traversed unsafe sentinel ${unsafeSentinelPath}`);
  }

  const elementsByPage = await Promise.all(pages.map((page) => jsonRequest(`/api/pages/${page.id}/elements`)));
  const elements = elementsByPage.flat();
  assert.ok(elements.length > 0, 'analysis returned no elements');
  assert.ok(elements.some((element) => element.actionClassification === 'SAFE'), 'analysis returned no SAFE element');
  assert.ok(elements.some((element) => element.actionClassification === 'MUTATING'), 'analysis returned no MUTATING element');
  const unknown = elements.find((element) => element.actionClassification === 'UNKNOWN' && element.accessibleName === 'More options');
  assert.ok(unknown, 'analysis returned no fixture UNKNOWN element');
  assert.equal(unknown.manualInclusionApproved, false, 'fixture UNKNOWN element was unexpectedly approved');

  const dashboard = pages.find((page) => new URL(page.url).pathname === '/dashboard.html');
  const screenshotResponse = await fetch(`${backendUrl}/api/pages/${dashboard.id}/screenshot`);
  assert.equal(screenshotResponse.status, 200);
  assert.equal(screenshotResponse.headers.get('content-type'), 'image/png');
  const screenshot = Buffer.from(await screenshotResponse.arrayBuffer());
  assert.deepEqual([...screenshot.subarray(0, 8)], [137, 80, 78, 71, 13, 10, 26, 10]);

  const generated = await jsonRequest(`/api/analyses/${started.id}/document`, {
    method: 'POST', body: JSON.stringify({ language: 'en', type: 'user_manual' })
  });
  assert.equal(generated.type, 'user_manual');
  assert.ok(generated.sections.length > 0, 'generated manual has no sections');
  assert.ok(!manualContent(generated).includes('More options'), 'unapproved UNKNOWN was included in the manual');
  assertCleanManual(generated, elements);

  const approved = await jsonRequest(`/api/elements/${unknown.id}/manual-inclusion`, {
    method: 'PUT', body: JSON.stringify({ approved: true })
  });
  assert.equal(approved.manualInclusionApproved, true, JSON.stringify(approved));
  const invalidated = await fetch(`${backendUrl}/api/documents/${generated.id}`);
  assert.equal(invalidated.status, 404, 'manual approval must invalidate the existing document');

  const regenerated = await jsonRequest(`/api/analyses/${started.id}/document`, {
    method: 'POST', body: JSON.stringify({ language: 'en', type: 'user_manual' })
  });
  assert.notEqual(regenerated.id, generated.id, 'manual approval must require a regenerated document');
  assert.ok(manualContent(regenerated).includes('More options'), 'approved UNKNOWN was absent from regenerated manual');
  assertCleanManual(regenerated, elements);

  const sectionToHide = regenerated.sections.find((section) => section.screenshotId !== null) || regenerated.sections[0];
  const updated = await jsonRequest(`/api/documents/${regenerated.id}`, {
    method: 'PUT',
    body: JSON.stringify({
      title: 'Updated E2E user manual',
      sections: regenerated.sections.map((section) => ({
        id: section.id,
        title: section.id === sectionToHide.id ? 'Updated E2E section' : section.title,
        content: section.id === sectionToHide.id ? 'Updated E2E guidance.' : section.content,
        hidden: section.id === sectionToHide.id
      }))
    })
  });
  const updatedSection = updated.sections.find((section) => section.id === sectionToHide.id);
  assert.equal(updated.title, 'Updated E2E user manual');
  assert.equal(updatedSection.content, 'Updated E2E guidance.');
  assert.equal(updatedSection.hidden, true, `updated document response must expose the hidden section state: ${JSON.stringify(updated)}`);
  assert.equal(updatedSection.sourcePageId, sectionToHide.sourcePageId);
  assert.equal(updatedSection.screenshotId, sectionToHide.screenshotId);

  const readBack = await jsonRequest(`/api/documents/${regenerated.id}`);
  const readBackSection = readBack.sections.find((section) => section.id === sectionToHide.id);
  assert.equal(readBack.title, 'Updated E2E user manual');
  assert.equal(readBackSection.content, 'Updated E2E guidance.');
  assert.equal(readBackSection.hidden, true, `read-back document response must expose the hidden section state: ${JSON.stringify(readBack)}`);
  assert.equal(readBackSection.sourcePageId, sectionToHide.sourcePageId);
  assert.equal(readBackSection.screenshotId, sectionToHide.screenshotId);

  console.log(`E2E passed: authenticated=true, status=COMPLETED, pages=${pages.length}, elements=${elements.length}, screenshot=PNG, manual=documented`);
}

main().catch((error) => {
  console.error(`E2E failed. Ensure backend and fixture are running: ${error.message}`);
  process.exitCode = 1;
});
