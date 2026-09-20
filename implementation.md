# SystemGuideForge
## Implementación actual del MVP

## 1. Flujo técnico

### Registrar y configurar

1. Crear `PROJECT` y `TARGET_APPLICATION`.
2. Validar la URL local, el protocolo, el host y las redirecciones permitidas.
3. Guardar URL base, URL de login, configuración del crawler y credenciales cifradas.
4. Excluir credenciales de respuestas, logs, screenshots y evidencia.

### Probar acceso

1. Abrir un contexto aislado de Playwright.
2. Navegar a la URL de login.
3. Completar el formulario tradicional con secretos en memoria.
4. Verificar el resultado y limpiar el contexto.

### Ejecutar análisis

1. Rechazar el inicio si ya existe un análisis `RUNNING` (`MAX_ACTIVE_ANALYSES = 1`).
2. Crear `ANALYSIS` en estado `RUNNING`.
3. Ejecutar el `ScreenAnalysisAdapter` de forma síncrona.
4. Persistir la pantalla inicial y las páginas descubiertas por el adaptador.
5. Continuar únicamente con enlaces de mismo origen clasificados como `SAFE`, respetando la profundidad configurada y los límites de captura.
6. Persistir elementos y screenshots sanitizados en PostgreSQL; `ScreenshotRepository` guarda los bytes como `BYTEA`.
7. Finalizar como `COMPLETED` o `FAILED`.

La ejecución no ofrece recuperación automática: si falla después de persistir una o más páginas, esas evidencias pueden quedar asociadas a un análisis `FAILED`; el backend no promete rollback de toda la evidencia. La interfaz conserva ese registro y su evidencia para inspección, los marca como incompletos y permite iniciar un análisis nuevo mediante `POST /api/applications/{id}/analyses`. Ese reintento usa la configuración actual de la aplicación, navega al identificador nuevo devuelto y no altera ni elimina el análisis fallido. La generación de documentos continúa bloqueada para análisis `FAILED`. El análisis no ejecuta controles: enlaces no `SAFE`, botones, formularios y acciones `MUTATING` o `UNKNOWN` no se ejecutan. La profundidad válida es de 0 a 5 (2 por defecto si la API omite el valor), con un máximo de 100 páginas persistidas incluida la inicial y un presupuesto global de 500 enlaces evaluados. Por página, la detección inspecciona hasta 500 `button`, 500 `a`, 500 `input` y 500 `textarea`; se toma una captura PNG de página completa después de enmascarar los campos sensibles.

### Generar y editar el manual

La generación es determinista y usa únicamente la evidencia observada:

- Idiomas admitidos: `en` y `es`.
- Tipo admitido: `user_manual`.
- Si ya existe un documento con el mismo análisis, idioma y tipo, la solicitud devuelve ese borrador y conserva las ediciones.
- Antes de generar el manual, una persona puede aprobar o retirar la inclusión documental de un elemento `UNKNOWN`. Esta decisión no cambia su clasificación ni autoriza crawling o ejecución.
- El manual incluye controles `SAFE` y elementos `UNKNOWN` aprobados, con instrucciones funcionales; omite acciones mutantes, elementos desconocidos sin aprobar, selectores y lenguaje técnico de clasificación.
- Al cambiar una aprobación de inclusión, el sistema elimina transaccionalmente el borrador y sus secciones para impedir reutilizar un borrador obsoleto. La interfaz informa que se debe generar uno nuevo.
- Si cambia el idioma o el tipo, el sistema muestra una advertencia localizada y reemplaza transaccionalmente las secciones y el contenido editable. **Las ediciones anteriores se destruyen.**
- La edición posterior permite cambiar el título y, para cada sección, el título, contenido, orden y visibilidad. Una sección marcada como `hidden` se conserva para edición y trazabilidad, pero se excluye del borrador orientado a lectura; los resultados del análisis no son editables desde este flujo.

## 2. Política de acciones

| Clasificación | Comportamiento |
| --- | --- |
| `SAFE` | Puede habilitar el crawling de un enlace de navegación de solo lectura. |
| `MUTATING` | Se bloquea; nunca se ejecuta. |
| `UNKNOWN` | Se bloquea por defecto; puede aprobarse solo para incluir instrucciones en el manual, sin ejecutarse. |

El análisis no adivina el efecto de un control ni envía formularios, confirma operaciones, modifica datos o descarga contenido cuyo efecto no sea claramente de solo lectura.

## 3. Seguridad

- Cifrar credenciales en reposo con `SGF_CREDENTIAL_KEY`; no hay clave predeterminada.
- Mantener secretos descifrados solo durante la operación de acceso/análisis.
- No escribir credenciales, cookies, tokens ni headers sensibles en logs, screenshots, respuestas API o evidencia.
- Usar contextos de navegador aislados y limpiar cada contexto al finalizar.
- Restringir navegación a sistemas locales autorizados y rechazar destinos no permitidos: el backend admite HTTP(S) solo en `localhost`, `127.0.0.1` y `::1`/`[::1]`.

## 4. Contratos principales

```java
public interface ScreenAnalysisAdapter {
    ScreenAnalysisResult analyze(TargetApplication application,
                                 String username,
                                 String password);
}

public interface ScreenshotRepository extends JpaRepository<Screenshot, String> {
}
```

No existen contratos de IA, workflows, DOCX ni almacenamiento de archivos en el MVP.

## 5. API y configuración

REST conecta Angular con Spring Boot y `openapi.yaml` describe las operaciones implementadas para proyectos, aplicaciones, prueba de acceso, análisis, evidencia y documentos.

- Backend: `http://localhost:8080`.
- Frontend: `http://localhost:4200`.
- Proxy de desarrollo: `/api` → `http://127.0.0.1:8080`.
- PostgreSQL Compose: `127.0.0.1:15432`, imagen `postgres:16-alpine`.
- `ddl-auto=validate`; Flyway V1–V8 gestiona el esquema.

Compose exige `POSTGRES_PASSWORD`. El backend admite `SGF_DB_URL`, `SGF_DB_USERNAME` y `SGF_DB_PASSWORD`; la clave de credenciales debe configurarse mediante `SGF_CREDENTIAL_KEY` o la propiedad Spring `sgf.credential-key`, sin valor predeterminado. Los flujos fixture/E2E admiten `SGF_BACKEND_URL`, `SGF_FIXTURE_URL`, `FIXTURE_USERNAME` y `FIXTURE_PASSWORD`. El adaptador requiere Chromium de Playwright instalado. Ver `README.md` para defaults y sintaxis POSIX/PowerShell.

## 6. Verificación

Evidencia disponible en el árbol de trabajo:

| Comando | Evidencia actual |
| --- | --- |
| `cd backend && ./mvnw test` | Los informes Surefire presentes registran 89 pruebas, 0 fallos, 0 errores y 0 omitidas. No se ejecutó en esta actualización documental. |
| `cd frontend && npm test` | Hay 36 casos `it` declarados; no se ejecutaron en esta actualización documental. |
| `cd frontend && npm run build` | El script está definido; no se ejecutó en esta actualización documental. |
| `git diff --check` | Debe ejecutarse para validar formato; no se afirma un resultado previo. |
| `cd test-target && npm run e2e` y browser manual | Requieren el stack local; no se afirma una ejecución durante esta actualización. |

## 7. Fuera de alcance

IA, workflows, captura guiada de operaciones mutantes, DOCX/PDF, sistemas de producción, SSO/OAuth/MFA, colaboración, multiusuario, roles, multi-tenant, análisis de repositorios, microservicios y almacenamiento remoto requieren trabajo futuro.
