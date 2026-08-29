# SystemGuideForge

SystemGuideForge (SGF) es una herramienta web de uso personal para analizar sistemas web locales y generar un borrador editable de manual a partir de evidencia real.

## Alcance MVP

El MVP permite:

1. Registrar un sistema local.
2. Configurar su URL, login tradicional y credenciales de prueba.
3. Probar el acceso y autenticarse con usuario y contraseña.
4. Analizar módulos y pantallas en modo seguro y de solo lectura.
5. Detectar elementos de interfaz y tomar screenshots.
6. Bloquear acciones mutantes o desconocidas.
7. Generar un borrador editable de manual.

Las credenciales se protegen desde el ingreso: nunca aparecen en logs, screenshots ni prompts de IA. La IA no es necesaria para el MVP.

## Stack tecnológico

- Frontend: Angular, TypeScript, Angular Material, Signals y RxJS cuando sea necesario.
- Backend: Java, Spring Boot, Spring Web, Spring Data JPA y Bean Validation.
- Arquitectura: Modular Monolith con arquitectura hexagonal pragmática.
- Persistencia: PostgreSQL y Flyway.
- Automatización: Playwright Java.
- Archivos MVP: filesystem local.
- API: REST documentada con OpenAPI.
- Desarrollo: Docker Compose.

## Estructura del repositorio

La documentación vigente está en la raíz:

```text
systemguideforge/
├── frontend/
├── backend/
├── test-target/
├── README.md
├── specification.md
├── architecture.md
├── implementation.md
└── openapi.yaml
```

## Fuera del MVP

Quedan como evolución futura, no como requisitos inmediatos: IA, workflows y captura guiada de operaciones mutantes, exportación DOCX, soporte de sistemas en producción, SSO, MFA, colaboración, multiusuario, análisis de repositorios, microservicios y almacenamiento remoto.

## Documentación

- `specification.md`: alcance funcional y criterios del MVP.
- `architecture.md`: arquitectura, componentes y modelo de datos del MVP.
- `implementation.md`: flujo técnico, seguridad y reglas de implementación.
- `openapi.yaml`: contrato REST limitado al MVP.

## Run the backend locally

Local backend persistence uses PostgreSQL and Flyway. Docker is required for the database:

```bash
docker compose up -d postgres
```

Wait until the PostgreSQL healthcheck is healthy, then start the backend with Java 25:

```bash
cd backend
./mvnw spring-boot:run
```

Flyway creates the schema on backend startup. Existing H2 files are not imported or migrated; create any required local data again in PostgreSQL.

The default database settings are:

| Variable | Default |
| --- | --- |
| `SGF_DB_URL` | `jdbc:postgresql://localhost:15432/systemguideforge` |
| `SGF_DB_USERNAME` | `systemguideforge` |
| `SGF_DB_PASSWORD` | `systemguideforge` |

The compose database uses these same defaults. The backend remains on port `8080`, and the frontend and fixture workflows remain on ports `4200` and `4173` respectively. Tests use an isolated PostgreSQL Testcontainers instance and do not fall back to H2; Docker must be available to run them.

Run backend tests with `./mvnw test` from `backend`.

The backend requires `SGF_CREDENTIAL_KEY` to encrypt credentials; there is no default key. `sgf.credential-key` may be configured explicitly in test/local environments, but must never be shared or used in production.

`POST /applications/{id}/test-access` performs a real traditional login through Playwright using an isolated browser context. Chromium must be installed for Playwright. If the browser is unavailable, the result does not indicate successful authentication.

## Aplicación fixture local

La aplicación determinista para probar SystemGuideForge vive en `test-target/` y usa únicamente Node.js:

```bash
cd test-target
npm start
```

Abrí `http://127.0.0.1:4173`. Credenciales de fixture: `fixture-user` / `fixture-password` (son datos de prueba, no secretos reales). La fixture incluye login, sesión protegida, logout, controles seguros, mutantes y ambiguos, y un campo sensible para validar el masking de screenshots. El smoke test reproducible se ejecuta con `cd test-target && npm test`.

## E2E contra backend y fixture

El smoke test unitario (`npm test`) no requiere backend. El E2E requiere ambos servicios levantados, Chromium de Playwright instalado y Java 25:

Terminal 1:

```bash
cd test-target
npm start
```

Terminal 2 (after `docker compose up -d postgres`):

```bash
cd backend
set SGF_CREDENTIAL_KEY=local-only-test-key
./mvnw spring-boot:run
```

En PowerShell usar `$env:SGF_CREDENTIAL_KEY="local-only-test-key"` antes de `./mvnw spring-boot:run`. Con ambos servicios activos, ejecutar:

```bash
cd test-target
npm run e2e
```

El backend puede configurarse con `SGF_BACKEND_URL`; la fixture con `SGF_FIXTURE_URL`, `FIXTURE_USERNAME` y `FIXTURE_PASSWORD`. El E2E crea un proyecto y aplicación temporales, ejecuta test-access y analysis, y verifica autenticación, estado `COMPLETED`, páginas, elementos y screenshot PNG.
