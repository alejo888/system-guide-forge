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

## Ejecutar backend

Requiere Java 25:

```bash
cd backend
./mvnw spring-boot:run
```

Las pruebas se ejecutan con `./mvnw test`.

El backend exige `SGF_CREDENTIAL_KEY` para cifrar credenciales; no existe una clave predeterminada. `sgf.credential-key` puede configurarse explícitamente en entornos de test/local, pero nunca debe compartirse ni usarse en producción.

`POST /applications/{id}/test-access` ejecuta un login tradicional real mediante Playwright, con un contexto de navegador aislado. Requiere tener instalado el navegador Chromium de Playwright. Si el navegador no está disponible, el resultado no indica autenticación exitosa.

La configuración H2 con `ddl-auto=update` incluida en el backend es únicamente para desarrollo local; no representa la configuración de producción documentada (PostgreSQL/Flyway).
