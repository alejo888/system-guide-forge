# SystemGuideForge

SystemGuideForge (SGF) es una herramienta web de uso personal para analizar sistemas web locales de forma segura y generar un borrador editable de manual a partir de evidencia observada. El MVP no requiere IA.

## Inicio rápido

### 1. Iniciar PostgreSQL

Docker Compose requiere `POSTGRES_PASSWORD` y no lo toma de `SGF_DB_PASSWORD`. Para que Compose y el backend usen la misma contraseña local, exportá ambas variables con un placeholder de desarrollo:

```bash
export POSTGRES_PASSWORD=local-only-postgres-password
export SGF_DB_PASSWORD="$POSTGRES_PASSWORD"
docker compose up -d postgres
```

En PowerShell:

```powershell
$env:POSTGRES_PASSWORD="local-only-postgres-password"
$env:SGF_DB_PASSWORD=$env:POSTGRES_PASSWORD
docker compose up -d postgres
```

La base queda disponible en `127.0.0.1:15432`. El backend usa las variables `SGF_DB_URL`, `SGF_DB_USERNAME` y `SGF_DB_PASSWORD`; si no exportás `SGF_DB_PASSWORD`, su valor local predeterminado es `systemguideforge` según `application.properties`, por lo que debe coincidir con la contraseña configurada para Compose.

### 2. Iniciar el backend

Se requiere Java 25 y una clave de credenciales. `SGF_CREDENTIAL_KEY` es obligatoria y no tiene valor predeterminado:

```bash
export SGF_CREDENTIAL_KEY=local-only-test-key
cd backend && ./mvnw spring-boot:run
```

En PowerShell:

```powershell
$env:SGF_CREDENTIAL_KEY="local-only-test-key"
cd backend
./mvnw spring-boot:run
```

El backend escucha en `http://localhost:8080`. Flyway aplica las migraciones V1–V8 al iniciar y Hibernate usa `ddl-auto=validate`.

### 3. Iniciar el frontend

```bash
cd frontend
npm install
npm start
```

La aplicación queda en `http://localhost:4200`. El proxy de Angular reenvía las solicitudes `/api` a `http://127.0.0.1:8080`, evitando configurar CORS para el desarrollo local.

## Variables de entorno

| Variable | Requerida | Uso | Valor por defecto |
| --- | --- | --- | --- |
| `POSTGRES_PASSWORD` | Sí para Compose | Contraseña del contenedor PostgreSQL | No tiene; Compose falla si falta |
| `SGF_CREDENTIAL_KEY` | Sí para backend | Cifra las credenciales guardadas | No tiene |
| `SGF_DB_URL` | No | URL JDBC del backend | `jdbc:postgresql://localhost:15432/systemguideforge` |
| `SGF_DB_USERNAME` | No | Usuario JDBC | `systemguideforge` |
| `SGF_DB_PASSWORD` | No | Contraseña JDBC | `systemguideforge` |
| `SGF_BACKEND_URL` | No | URL del backend para E2E | `http://127.0.0.1:8080` |
| `SGF_FIXTURE_URL` | No | URL de la fixture para E2E | `http://127.0.0.1:4173` |
| `FIXTURE_USERNAME` | No | Usuario de la fixture | `fixture-user` |
| `FIXTURE_PASSWORD` | No | Contraseña de la fixture | `fixture-password` |

Los valores de fixture son datos de prueba. No uses secretos reales en comandos, logs, screenshots ni archivos versionados.

## Fixture local y E2E

La fixture determinista vive en `test-target/` y escucha en `http://127.0.0.1:4173`:

```bash
cd test-target && npm start
```

El flujo E2E requiere PostgreSQL, backend, fixture, Java 25 y Chromium de Playwright instalados. Ejecutalo con `cd test-target && npm run e2e` después de levantar esos servicios. Esta documentación no ejecuta la validación manual/browser de ese flujo.

## Alcance del MVP

Incluye registro de sistemas locales, login tradicional, prueba de acceso, análisis síncrono seguro, detección de páginas y elementos, screenshots sanitizados y generación de un manual editable. Antes de generar el manual, una persona puede incluir un elemento `UNKNOWN` en la documentación; esa aprobación solo afecta el contenido del manual y nunca habilita su ejecución. La edición del MVP permite cambiar el título y, en cada sección, el título, contenido, orden y visibilidad; no edita los resultados del análisis.

El análisis ejecuta el adaptador de forma síncrona, recorre únicamente enlaces clasificados como `SAFE` y nunca ejecuta controles. Las acciones `MUTATING` y `UNKNOWN` quedan bloqueadas. Si cambia una aprobación de inclusión manual, se elimina el borrador existente para que se genere uno nuevo y no se reutilice contenido obsoleto. No se promete persistencia parcial ni recuperación automática ante fallos.

Fuera de alcance: producción, SSO/OAuth/MFA, workflows, IA, DOCX/PDF, colaboración, multiusuario, roles, multi-tenant, análisis de repositorios, microservicios y almacenamiento remoto.

## Documentación

- `specification.md`: alcance y comportamiento funcional.
- `architecture.md`: arquitectura implementada y persistencia.
- `implementation.md`: flujos, seguridad y configuración técnica.
- `openapi.yaml`: contrato REST del MVP.
- `docs/agent-guidelines.md`: reglas para inspección segura.

## Evidencia de verificación disponible

| Comprobación | Evidencia actual |
| --- | --- |
| `cd backend && ./mvnw test` | Los informes Surefire locales registran 86 pruebas, 0 fallos, 0 errores y 0 omitidas. No se ejecutó durante esta actualización documental. |
| `cd frontend && npm test` | El script está definido y hay 34 casos `it` declarados; no hay un informe de ejecución disponible. |
| `cd frontend && npm run build` | El script está definido; no hay un resultado de build disponible. |
| `git diff --check` | Pasa; Git solo informó la normalización habitual de finales de línea LF/CRLF. |
| `cd test-target && npm run e2e` y validación manual en navegador | E2E pasa: autenticación correcta, análisis `COMPLETED`, 2 páginas, 14 elementos, screenshot PNG y manual generado. La validación manual en navegador no se realizó. |

## Versiones verificadas

- Angular y Angular CLI `21.2.21`; TypeScript `5.9.3`.
- Java `25`; Spring Boot `3.5.6`; Playwright Java `1.55.0`.
- PostgreSQL `16-alpine`; Testcontainers `1.21.4`.
