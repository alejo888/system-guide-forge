# SystemGuideForge
## Especificación funcional vigente del MVP

## 1. Objetivo

Permitir que una persona documente un sistema web local recorriéndolo de forma segura y de solo lectura, conserve evidencia de páginas y elementos, y genere un borrador editable de manual.

## 2. Alcance

### Incluye

- Uso personal contra sistemas web locales autorizados.
- Registro de una aplicación, URL base, URL de login y credenciales de prueba protegidas.
- Login tradicional mediante usuario y contraseña.
- Prueba de acceso y análisis síncrono de solo lectura.
- Crawling únicamente de enlaces clasificados como `SAFE`.
- Detección de páginas y elementos relevantes.
- Screenshots sanitizados almacenados en PostgreSQL como `BYTEA`.
- Bloqueo de controles `MUTATING` y `UNKNOWN`; ningún control se ejecuta durante el análisis.
- Generación determinista de un manual con idioma `en` o `es` y tipo `user_manual`.
- Edición del título y de las secciones del documento generado.

### No incluye

- Edición de resultados del análisis.
- Persistencia parcial o recuperación automática de análisis fallidos.
- Sistemas de producción o accesibles fuera del entorno local.
- SSO, OAuth, MFA, workflows, captura guiada de operaciones mutantes o validación automática de funcionalidades.
- IA como requisito, exportación DOCX/PDF, colaboración, multiusuario, roles, multi-tenant o versionado avanzado.
- Análisis de repositorios, microservicios o almacenamiento remoto.

## 3. Flujo principal

```text
Registrar aplicación
      ↓
Configurar acceso
      ↓
Probar acceso y autenticarse
      ↓
Ejecutar análisis síncrono y seguro
      ↓
Revisar páginas y elementos
      ↓
Generar manual en en/es
      ↓
Editar título y secciones
```

## 4. Requisitos funcionales

- **RF01.** Registrar y editar una aplicación web local.
- **RF02.** Configurar URL base, URL de login y credenciales de prueba.
- **RF03.** Probar conectividad y autenticación tradicional.
- **RF04.** Ejecutar un análisis síncrono de solo lectura.
- **RF05.** Detectar páginas y elementos relevantes.
- **RF06.** Tomar screenshots sin credenciales ni secretos y almacenarlos en PostgreSQL.
- **RF07.** Clasificar acciones como `SAFE`, `MUTATING` o `UNKNOWN`.
- **RF08.** Recorrer solo enlaces `SAFE`; no ejecutar controles.
- **RF09.** Consultar y revisar los resultados del análisis.
- **RF10.** Generar un `user_manual` en `en` o `es`.
- **RF11.** Reutilizar el borrador cuando coinciden análisis, idioma y tipo.
- **RF12.** Al cambiar idioma o tipo, mostrar una advertencia localizada y reemplazar transaccionalmente secciones y contenido editable, destruyendo las ediciones previas.
- **RF13.** Editar el título y las secciones del manual generado.

## 5. Requisitos no funcionales

- **RNF01. Seguridad.** Las credenciales se almacenan cifradas y nunca se escriben en logs.
- **RNF02. Privacidad.** Las credenciales no aparecen en screenshots, evidencia ni respuestas API.
- **RNF03. Solo lectura.** El análisis no ejecuta controles ni muta datos del sistema objetivo.
- **RNF04. Trazabilidad.** Las secciones del manual conservan referencias a la evidencia de origen.
- **RNF05. Configuración local.** El MVP funciona con PostgreSQL local mediante Docker Compose, Flyway y un backend Spring Boot.
- **RNF06. Simplicidad.** Solo puede haber un análisis `RUNNING` a la vez.

## 6. Criterio de aceptación

El MVP es funcional cuando, contra un sistema web local con login tradicional, permite probar el acceso, autenticarse, ejecutar el análisis síncrono sin mutar datos, recorrer enlaces `SAFE`, detectar páginas y elementos, guardar screenshots sanitizados y producir un `user_manual` editable en `en` o `es`.

## 7. Verificación registrada

| Comprobación | Resultado |
| --- | --- |
| `cd backend && ./mvnw test` | 72 tests pasan |
| `cd frontend && npm test` | 30 tests pasan |
| `cd frontend && npm run build` | Pasa |
| `git diff --check` | Pasa |
| Fixture/E2E y validación manual/browser | Requiere el stack local; no se ejecutó en esta actualización documental |

## 8. Evolución futura

IA, workflows, captura guiada de operaciones mutantes, exportación DOCX/PDF, soporte de producción, ejecución asíncrona y almacenamiento remoto se evaluarán después del MVP.
