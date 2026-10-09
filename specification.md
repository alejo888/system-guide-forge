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
- Crawling únicamente de enlaces de mismo origen clasificados como `SAFE`, con profundidad de 0 a 5, hasta 100 páginas incluida la inicial y un presupuesto global de 500 enlaces evaluados.
- Detección de hasta 500 elementos de cada tipo `button`, `a`, `input` y `textarea` por página.
- Screenshots PNG sanitizados de página completa, almacenados en PostgreSQL como `BYTEA`.
- Bloqueo de controles `MUTATING` y `UNKNOWN`; ningún control se ejecuta durante el análisis.
- Revisión posterior al análisis para aprobar la inclusión documental de elementos `UNKNOWN` que no son campos de formulario, sin habilitar su ejecución.
- Generación determinista de un manual con idioma `en` o `es` y tipo `user_manual`; incluye controles `SAFE`, elementos `UNKNOWN` aprobados y, automáticamente, los campos de formulario con nombre (solo su etiqueta, nunca su valor) y las acciones `MUTATING` con nombre, que se describen sin ejecutarse; no usa etiquetas de clasificación ni selectores técnicos. Cuando la página inicial es de login, el manual incluye como primer paso una sección de inicio de sesión derivada de las etiquetas detectadas.
- Carga automática del borrador existente al abrir un análisis, sin bloquear la generación de uno nuevo.
- Edición del título y de las secciones del documento generado.
- Exportación del borrador persistido a DOCX.

### No incluye

- Edición de resultados del análisis.
- Recuperación automática o reintento sobre el mismo identificador de análisis. Una falla puede dejar evidencia parcial asociada al análisis fallido; ese registro se conserva para inspección y se marca como incompleto. La persona puede iniciar un análisis nuevo con la configuración actual de la aplicación, sin alterar ni eliminar el registro fallido.
- Sistemas de producción o accesibles fuera del entorno local.
- SSO, OAuth, MFA, workflows, captura guiada de operaciones mutantes o validación automática de funcionalidades.
- IA como requisito, exportación PDF, colaboración, multiusuario, roles, multi-tenant o versionado avanzado. El MVP permite exportar el borrador persistido a DOCX.
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
Aprobar opcionalmente elementos UNKNOWN para documentación
      ↓
Generar manual en en/es
      ↓
Editar título y secciones
```

## 4. Requisitos funcionales

- **RF01.** Registrar y editar una aplicación web local.
- **RF02.** Configurar URL base, URL de login y credenciales de prueba en hosts HTTP(S) locales permitidos: `localhost`, `127.0.0.1` o `::1`/`[::1]`.
- **RF03.** Probar conectividad y autenticación tradicional.
- **RF04.** Ejecutar un análisis síncrono de solo lectura.
- **RF05.** Detectar páginas y elementos relevantes.
- **RF06.** Tomar screenshots sin credenciales ni secretos y almacenarlos en PostgreSQL.
- **RF07.** Clasificar acciones como `SAFE`, `MUTATING` o `UNKNOWN`.
- **RF08.** Recorrer solo enlaces `SAFE` de mismo origen, respetando los límites configurados y sin ejecutar controles.
- **RF09.** Consultar y revisar los resultados del análisis.
- **RF10.** Generar un `user_manual` en `en` o `es`.
- **RF11.** Reutilizar el borrador cuando coinciden análisis, idioma y tipo.
- **RF12.** Al cambiar idioma o tipo, mostrar una advertencia localizada y reemplazar transaccionalmente secciones y contenido editable, destruyendo las ediciones previas.
- **RF13.** Editar el título y las secciones del manual generado.
- **RF14.** Permitir aprobar o retirar la inclusión documental de un elemento `UNKNOWN` antes de generar el manual, sin ejecutar el elemento ni ampliar el crawling. Los campos de formulario `UNKNOWN` con nombre no requieren esta aprobación.
- **RF15.** Incluir en el manual los elementos `SAFE` y `UNKNOWN` aprobados y, sin necesidad de aprobación, los campos de formulario con nombre (`textarea` y los `input` de tipo texto, contraseña, correo, número, búsqueda, teléfono, URL, fecha u hora, casilla de verificación o botón de opción; también los `input` sin tipo capturado de análisis anteriores) y las acciones `MUTATING` con nombre, con instrucciones funcionales y sin selectores ni lenguaje técnico de clasificación. De los campos se usa solo la etiqueta, nunca su valor; las acciones `MUTATING` se describen sin ejecutarse. Los controles incluidos automáticamente sin nombre o con una etiqueta censurada (`[redacted]`) se omiten. Los demás controles `UNKNOWN` (botones, enlaces, `input` de tipo `button`, `reset`, `submit`, `image`, `file`, `hidden`, `color`, `range` u otros tipos) siguen requiriendo aprobación manual. Las casillas de verificación se describen como opciones para marcar o desmarcar y los botones de opción como opciones para elegir; los demás tipos de `input`, y los elementos analizados antes de capturar el tipo, conservan la redacción de ingreso de texto. Los enlaces con nombre y los botones con nombre dentro de una zona de navegación (por ejemplo, "Cerrar sesión" en la cabecera) presentes en todas las páginas recorridas se documentan una sola vez, en la página inicial. Las acciones `MUTATING` cuyo nombre contiene un verbo destructivo (eliminar, borrar, quitar, delete, remove, erase; no desactivar ni deshabilitar) agregan una advertencia; los enlaces `MUTATING` se redactan como enlaces, y cuando una sección tiene más de 10 casillas de verificación se listan ordenadas por nombre; los botones de opción conservan el orden capturado.
- **RF16.** Eliminar el borrador existente al cambiar una aprobación de inclusión documental, para exigir una nueva generación sin contenido obsoleto.
- **RF17.** Listar en el Resumen todos los sistemas registrados a partir del backend y conservar en el navegador únicamente las preferencias de idioma; los sistemas, la evidencia y los documentos deben permanecer en el backend.
- **RF18.** Normalizar las rutas excluidas como prefijos absolutos, quitar barras finales salvo en `/`, eliminar duplicados preservando el primer orden y rechazar consultas, fragmentos, escapes porcentuales, más de 50 rutas o rutas de más de 200 caracteres.
- **RF19.** Cuando un análisis falle, conservar el registro y la evidencia parcial visibles, marcarlos como incompletos y permitir iniciar mediante el endpoint de creación existente un análisis nuevo que use la configuración actual de la aplicación. La generación de documentos permanece bloqueada para análisis `FAILED`.
- **RF20.** Cuando la página inicial capturada sea de login, incluir en el manual generado una primera sección de inicio de sesión derivada de las etiquetas de usuario, contraseña y botón de envío detectadas en esa página.
- **RF21.** Al abrir un análisis, buscar y mostrar automáticamente el borrador existente, si lo hay, sin bloquear la posibilidad de generar uno nuevo.
- **RF22.** Permitir exportar el borrador persistido a DOCX, incrustando las capturas sanitizadas disponibles y excluyendo las secciones ocultas.
- **RF23.** Nombrar las secciones del manual con el `h1` visible de cada página cuando exista (si no, con el título) y agrupar en una sola sección las páginas cuyas rutas difieren solo en segmentos numéricos o UUID, conservando la evidencia por página.

## 5. Requisitos no funcionales

- **RNF01. Seguridad.** Las credenciales se almacenan cifradas y nunca se escriben en logs.
- **RNF02. Privacidad.** Las credenciales no aparecen en screenshots, evidencia ni respuestas API.
- **RNF03. Solo lectura.** El análisis no ejecuta controles ni muta datos del sistema objetivo.
- **RNF04. Trazabilidad.** Las secciones del manual conservan referencias a la evidencia de origen.
- **RNF05. Configuración local.** El MVP funciona con PostgreSQL local mediante Docker Compose, Flyway y un backend Spring Boot.
- **RNF06. Simplicidad.** Solo puede haber un análisis `RUNNING` a la vez.
- **RNF07. Estado del cliente.** `localStorage` no contiene credenciales, screenshots, resultados de análisis ni documentos completos.

## 6. Criterio de aceptación

El MVP es funcional cuando, contra un sistema web local con login tradicional, permite probar el acceso, autenticarse, ejecutar el análisis síncrono sin mutar datos, recorrer enlaces `SAFE`, detectar páginas y elementos, guardar screenshots sanitizados y producir un `user_manual` editable en `en` o `es`.

## 7. Verificación registrada

| Comprobación | Resultado |
| --- | --- |
| `cd backend && ./mvnw test` | Suite JUnit del backend; la CI (`.github/workflows/ci.yml`) la ejecuta en cada push y pull request. |
| `cd frontend && npm test` | Suite Karma del frontend; la CI (`.github/workflows/ci.yml`) la ejecuta en cada push y pull request. |
| `cd frontend && npm run build` | El script está definido; no se ejecutó en esta actualización documental. |
| `git diff --check` | Debe ejecutarse para validar formato; no se afirma un resultado previo. |
| Fixture/E2E y validación manual/browser | Requieren el stack local; no se afirma una ejecución durante esta actualización documental. |

## 8. Evolución futura

IA, workflows, captura guiada de operaciones mutantes, exportación PDF, soporte de producción, ejecución asíncrona y almacenamiento remoto se evaluarán después del MVP.
