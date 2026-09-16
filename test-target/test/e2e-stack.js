const http = require('node:http');
const net = require('node:net');
const path = require('node:path');
const { spawn } = require('node:child_process');

const rootDirectory = path.resolve(__dirname, '..', '..');
const targetDirectory = path.resolve(__dirname, '..');
const backendDirectory = path.join(rootDirectory, 'backend');
const frontendDirectory = path.join(rootDirectory, 'frontend');
const isWindows = process.platform === 'win32';
const retryAttempts = 60;
const retryDelayMs = 1000;

let backendProcess;
let fixtureProcess;
let frontendProcess;
let e2eProcess;
let browserE2eProcess;
let startedPostgres = false;
let cleaningUp = false;

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function runCommand(command, arguments_, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, arguments_, {
      cwd: options.cwd || rootDirectory,
      env: options.env || process.env,
      shell: isWindows,
      stdio: options.stdio || ['ignore', 'pipe', 'pipe']
    });
    let stdout = '';
    let stderr = '';

    child.stdout?.on('data', (chunk) => { stdout += chunk; });
    child.stderr?.on('data', (chunk) => { stderr += chunk; });
    child.once('error', reject);
    child.once('exit', (code, signal) => resolve({ code, signal, stdout, stderr }));
  });
}

function startProcess(command, arguments_, options = {}) {
  const child = spawn(command, arguments_, {
    cwd: options.cwd || rootDirectory,
    env: options.env || process.env,
    detached: !isWindows,
    shell: options.shell ?? isWindows,
    stdio: 'inherit'
  });
  child.once('error', (error) => {
    console.error(`Unable to start ${options.name || command}: ${error.message}`);
  });
  return child;
}

function isPortOccupied(port) {
  return new Promise((resolve) => {
    const socket = net.createConnection({ host: '127.0.0.1', port });
    const finish = (occupied) => {
      socket.removeAllListeners();
      socket.destroy();
      resolve(occupied);
    };
    socket.setTimeout(1000);
    socket.once('connect', () => finish(true));
    socket.once('timeout', () => finish(false));
    socket.once('error', () => finish(false));
  });
}

function requestStatus(url) {
  return new Promise((resolve, reject) => {
    const request = http.get(url, (response) => {
      response.resume();
      resolve(response.statusCode);
    });
    request.setTimeout(2000, () => request.destroy(new Error(`Timed out requesting ${url}`)));
    request.once('error', reject);
  });
}

async function waitFor(description, probe) {
  let lastError;
  for (let attempt = 1; attempt <= retryAttempts; attempt++) {
    try {
      if (await probe()) {
        console.log(`${description} is ready.`);
        return;
      }
    } catch (error) {
      lastError = error;
    }
    await sleep(retryDelayMs);
  }
  const detail = lastError ? ` Last error: ${lastError.message}` : '';
  throw new Error(`${description} was not ready after ${retryAttempts} attempts.${detail}`);
}

async function isComposePostgresRunning() {
  const result = await runCommand('docker', ['compose', 'ps', '--status', 'running', '-q', 'postgres']);
  if (result.code !== 0) {
    throw new Error(`Unable to inspect PostgreSQL with docker compose: ${result.stderr.trim() || result.stdout.trim() || `exit ${result.code}`}`);
  }
  return result.stdout.trim().length > 0;
}

async function assertInitialPorts(postgresAlreadyRunning) {
  const occupiedPorts = [];
  for (const port of [15432, 8080, 4173]) {
    if (await isPortOccupied(port)) occupiedPorts.push(port);
  }

  const unexpectedPorts = occupiedPorts.filter((port) => port !== 15432 || !postgresAlreadyRunning);
  if (unexpectedPorts.length > 0) {
    throw new Error(
      `Refusing to reuse existing services on port(s) ${unexpectedPorts.join(', ')}. Stop the unrelated or stale service(s) before running e2e:stack.`
    );
  }
}

async function stopProcess(child, name) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;

  if (isWindows) {
    const result = await runCommand('taskkill', ['/pid', String(child.pid), '/t', '/f']);
    if (result.code !== 0) {
      console.error(`Could not stop ${name}: ${result.stderr.trim() || `taskkill exited ${result.code}`}`);
    }
    return;
  }

  try {
    process.kill(-child.pid, 'SIGTERM');
  } catch (error) {
    if (error.code !== 'ESRCH') console.error(`Could not stop ${name}: ${error.message}`);
  }
}

async function cleanup() {
  if (cleaningUp) return;
  cleaningUp = true;

  await Promise.all([
    stopProcess(backendProcess, 'backend'),
    stopProcess(fixtureProcess, 'fixture'),
    stopProcess(frontendProcess, 'frontend')
  ]);

  if (startedPostgres) {
    const result = await runCommand('docker', ['compose', 'stop', 'postgres']);
    if (result.code !== 0) {
      console.error(`Could not stop PostgreSQL: ${result.stderr.trim() || result.stdout.trim() || `docker compose exited ${result.code}`}`);
    }
  }
}

async function runE2e() {
  return new Promise((resolve, reject) => {
    e2eProcess = startProcess(isWindows ? 'npm.cmd' : 'npm', ['run', 'e2e'], {
      cwd: targetDirectory,
      name: 'E2E suite'
    });
    e2eProcess.once('error', reject);
    e2eProcess.once('exit', (code, signal) => resolve({ code, signal }));
  });
}

async function runBrowserE2e() {
  return new Promise((resolve, reject) => {
    browserE2eProcess = startProcess(isWindows ? 'npm.cmd' : 'npm', ['run', 'e2e:browser'], {
      cwd: frontendDirectory,
      name: 'browser E2E suite'
    });
    browserE2eProcess.once('error', reject);
    browserE2eProcess.once('exit', (code, signal) => resolve({ code, signal }));
  });
}

async function main() {
  const browserMode = process.argv.slice(2).includes('--browser');
  const postgresPassword = process.env.POSTGRES_PASSWORD || process.env.SGF_DB_PASSWORD || 'systemguideforge';
  const databasePassword = process.env.SGF_DB_PASSWORD || postgresPassword;
  const credentialKey = process.env.SGF_CREDENTIAL_KEY || 'local-only-test-key';
  if (process.env.SGF_DB_PASSWORD && process.env.SGF_DB_PASSWORD !== postgresPassword) {
    throw new Error('SGF_DB_PASSWORD must match POSTGRES_PASSWORD for the PostgreSQL container started by e2e:stack.');
  }

  const postgresAlreadyRunning = await isComposePostgresRunning();
  await assertInitialPorts(postgresAlreadyRunning);

  if (!postgresAlreadyRunning) {
    const result = await runCommand('docker', ['compose', 'up', '-d', 'postgres'], {
      env: { ...process.env, POSTGRES_PASSWORD: postgresPassword }
    });
    if (result.code !== 0) {
      throw new Error(`Unable to start PostgreSQL: ${result.stderr.trim() || result.stdout.trim() || `docker compose exited ${result.code}`}`);
    }
    startedPostgres = true;
  }
  await waitFor('PostgreSQL on port 15432', () => isPortOccupied(15432));

  backendProcess = startProcess(isWindows ? 'mvnw.cmd' : './mvnw', ['spring-boot:run'], {
    cwd: backendDirectory,
    name: 'backend',
    env: {
      ...process.env,
      POSTGRES_PASSWORD: postgresPassword,
      SGF_DB_PASSWORD: databasePassword,
      SGF_CREDENTIAL_KEY: credentialKey
    }
  });
  await waitFor('Backend at http://127.0.0.1:8080', async () => (await requestStatus('http://127.0.0.1:8080/api/projects/readiness-probe')) === 404);

  fixtureProcess = startProcess(process.execPath, ['server.js'], {
    cwd: targetDirectory,
    name: 'fixture',
    shell: false
  });
  await waitFor('Fixture at http://127.0.0.1:4173', async () => (await requestStatus('http://127.0.0.1:4173/login.html')) === 200);

  const result = await runE2e();
  if (result.signal) {
    throw new Error(`E2E suite ended with ${result.signal}.`);
  }
  process.exitCode = result.code || 0;
  if (!browserMode || result.code) return;

  if (await isPortOccupied(4200)) {
    console.log('Angular on port 4200 is already running; reusing it.');
  } else {
    frontendProcess = startProcess(isWindows ? 'npm.cmd' : 'npm', ['start', '--', '--host', '127.0.0.1', '--port', '4200'], {
      cwd: frontendDirectory,
      name: 'frontend'
    });
  }
  await waitFor('Angular at http://127.0.0.1:4200', async () => (await requestStatus('http://127.0.0.1:4200')) === 200);

  const browserResult = await runBrowserE2e();
  if (browserResult.signal) {
    throw new Error(`Browser E2E suite ended with ${browserResult.signal}.`);
  }
  process.exitCode = browserResult.code || 0;
}

async function exitForSignal(signal) {
  if (cleaningUp) return;
  console.error(`Received ${signal}; cleaning up started services.`);
  await Promise.all([
    stopProcess(e2eProcess, 'E2E suite'),
    stopProcess(browserE2eProcess, 'browser E2E suite')
  ]);
  await cleanup();
  process.exit(signal === 'SIGINT' ? 130 : 143);
}

process.once('SIGINT', () => { void exitForSignal('SIGINT'); });
process.once('SIGTERM', () => { void exitForSignal('SIGTERM'); });

main()
  .catch((error) => {
    console.error(`E2E stack failed: ${error.message}`);
    process.exitCode = 1;
  })
  .finally(cleanup);
