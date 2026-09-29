# SystemGuideForge
## Implementación actual del MVP

## 1. Flujo técnico

### Registrar y configurar

1. Crear `PROJECT` y `TARGET_APPLICATION`.
2. Validar la URL local, el protocolo, el host y las redirecciones permitidas.
3. Guardar URL base, URL de login, configuración del crawler y credenciales cifradas.
4. Excluir credenciales de respuestas, logs, screenshots y evidencia.
5. Listar los sistemas registrados con `GET /api/applications`, ordenados por nombre y luego por identificador. El Resumen muestra cada sistema con su historial de análisis combinado, y la edición carga el sistema por identificador (`/edit/{id}` → `GET /api/applications/{id}`); un identificador inexistente muestra un error en lugar de un formulario vacío.

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

Por cada página, además del `<title>`, se captura el primer `h1` visible como `heading` (sanitizado con las mismas reglas de redacción que el título y limitado a 255 caracteres); es `null` si la página no tiene un `h1` visible.

La ejecución no ofrece recuperación automática: si falla después de persistir una o más páginas, esas evidencias pueden quedar asociadas a un análisis `FAILED`; el backend no promete rollback de toda la evidencia. La interfaz conserva ese registro y su evidencia para inspección, los marca como incompletos y permite iniciar un análisis nuevo mediante `POST /api/applications/{id}/analyses`. Ese reintento usa la configuración actual de la aplicación, navega al identificador nuevo devuelto y no altera ni elimina el análisis fallido. La generación de documentos continúa bloqueada para análisis `FAILED`. El análisis no ejecuta controles: enlaces no `SAFE`, botones, formularios y acciones `MUTATING` o `UNKNOWN` no se ejecutan. La profundidad válida es de 0 a 5 (2 por defecto si la API omite el valor), con un máximo de 100 páginas persistidas incluida la inicial y un presupuesto global de 500 enlaces evaluados. Por página, la detección inspecciona hasta 500 `button`, 500 `a`, 500 `input` y 500 `textarea`; se toma una captura PNG de página completa después de enmascarar los campos sensibles.

### Generar y editar el manual

La generación es determinista y usa únicamente la evidencia observada:

- Idiomas admitidos: `en` y `es`.
- Tipo admitido: `user_manual`.
- Si ya existe un documento con el mismo análisis, idioma y tipo, la solicitud devuelve ese borrador y conserva las ediciones.
- Antes de generar el manual, una persona puede aprobar o retirar la inclusión documental de un elemento `UNKNOWN`. Esta decisión no cambia su clasificación ni autoriza crawling o ejecución.
- El manual incluye controles `SAFE` y elementos `UNKNOWN` aprobados, con instrucciones funcionales; omite acciones mutantes, elementos desconocidos sin aprobar, selectores y lenguaje técnico de clasificación.
- Si la página inicial fue capturada como página de login (`PageKind.LOGIN`), el manual incluye una primera sección "Cómo ingresar al sistema" ("How to sign in"), generada a partir de las etiquetas de usuario, contraseña y botón de envío detectadas en esa página.
- Los nombres de sección prefieren el `h1` visible de la página (`heading`) sobre el `<title>`, que suele ser estático en aplicaciones de una sola página.
- Las páginas cuyas rutas difieren solo en segmentos numéricos o UUID se agrupan en una única sección por plantilla de ruta (por ejemplo, `/projects/{id}/board`), usando como ejemplo la primera página por URL. Solo se agrupan páginas del mismo módulo cuya ruta contiene al menos un segmento de identificador; las páginas que difieren únicamente en la consulta (`?tab=`) o el fragmento (`#/...`) conservan su propia sección. Si las páginas agrupadas muestran encabezados distintos, la sección usa un nombre genérico ("Detalle del elemento" / "Item details"). Los pasos se basan en los controles de la página de ejemplo y suman los elementos `UNKNOWN` aprobados en las demás páginas del grupo, para que ninguna aprobación manual se pierda. La evidencia (páginas, elementos y capturas) se conserva por página.
- Al abrir un análisis, la interfaz busca un borrador existente (`GET /api/analyses/{id}/document`) y lo muestra automáticamente si existe, con un indicador no bloqueante mientras la búsqueda está en curso; un 404 o cualquier otro error oculta el indicador sin bloquear la generación.
- Al cambiar una aprobación de inclusión, el sistema elimina transaccionalmente el borrador y sus secciones para impedir reutilizar un borrador obsoleto. La interfaz informa que se debe generar uno nuevo.
- Si cambia el idioma o el tipo, el sistema muestra una advertencia localizada y reemplaza transaccionalmente las secciones y el contenido editable. **Las ediciones anteriores se destruyen.**
- La edición posterior permite cambiar el título y, para cada sección, el título, contenido, orden y visibilidad. Una sección marcada como `hidden` se conserva para edición y trazabilidad, pero se excluye del borrador orientado a lectura; los resultados del análisis no son editables desde este flujo.
- El borrador persistido puede exportarse a DOCX (`GET /api/documents/{id}/export?format=docx`) mediante Apache POI: el título usa el estilo `Title` y cada sección visible el estilo `Heading1`; las secciones ocultas se excluyen. Las capturas sanitizadas en PNG se incrustan ajustadas a un máximo de 6.5"×9"; una captura no sanitizada, no PNG o corrupta se omite sin interrumpir la exportación. La respuesta es `400` si falta el parámetro `format` o no es `docx`, y `404` si el documento no existe.

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

public interface ManualExporter {
    ExportedManual export(String documentId);
    record ExportedManual(String title, byte[] bytes) {}
}
```

No existen contratos de IA, workflows, PDF ni almacenamiento de archivos en el MVP. El MVP permite exportar el borrador persistido a DOCX como archivo derivado no persistente mediante `ManualExporter` (implementado por `DocxManualExporter` con Apache POI).

## 5. API y configuración

REST conecta Angular con Spring Boot y `openapi.yaml` describe las operaciones implementadas para proyectos, aplicaciones, prueba de acceso, análisis, evidencia y documentos.

- Backend: `http://localhost:8080`.
- Frontend: `http://localhost:4200`.
- Proxy de desarrollo: `/api` → `http://127.0.0.1:8080`.
- PostgreSQL Compose: `127.0.0.1:15432`, imagen `postgres:16-alpine`.
- `ddl-auto=validate`; Flyway V1–V10 gestiona el esquema.

Compose exige `POSTGRES_PASSWORD`. El backend admite `SGF_DB_URL`, `SGF_DB_USERNAME` y `SGF_DB_PASSWORD`; la clave de credenciales debe configurarse mediante `SGF_CREDENTIAL_KEY` o la propiedad Spring `sgf.credential-key`, sin valor predeterminado. Los flujos fixture/E2E admiten `SGF_BACKEND_URL`, `SGF_FIXTURE_URL`, `FIXTURE_USERNAME` y `FIXTURE_PASSWORD`. El adaptador requiere Chromium de Playwright instalado. Ver `README.md` para defaults y sintaxis POSIX/PowerShell.

## 6. Verificación

Evidencia disponible en el árbol de trabajo:

| Comando | Evidencia actual |
| --- | --- |
| `cd backend && ./mvnw test` | Suite JUnit del backend; la CI (`.github/workflows/ci.yml`) la ejecuta en cada push y pull request. |
| `cd frontend && npm test` | Suite Karma del frontend; la CI (`.github/workflows/ci.yml`) la ejecuta en cada push y pull request. |
| `cd frontend && npm run build` | El script está definido; no se ejecutó en esta actualización documental. |
| `git diff --check` | Debe ejecutarse para validar formato; no se afirma un resultado previo. |
| `cd test-target && npm run e2e` y browser manual | Requieren el stack local; no se afirma una ejecución durante esta actualización. |

## 7. Fuera de alcance

IA, workflows, captura guiada de operaciones mutantes, PDF, sistemas de producción, SSO/OAuth/MFA, colaboración, multiusuario, roles, multi-tenant, análisis de repositorios, microservicios y almacenamiento remoto requieren trabajo futuro.
