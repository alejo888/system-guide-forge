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
- Revisión posterior al análisis para aprobar la inclusión documental de elementos `UNKNOWN`, sin habilitar su ejecución.
- Generación determinista de un manual con idioma `en` o `es` y tipo `user_manual`; incluye controles `SAFE` y elementos `UNKNOWN` aprobados, sin etiquetas de clasificación ni selectores técnicos.
- Edición del título y de las secciones del documento generado.

### No incluye

- Edición de resultados del análisis.
- Recuperación automática de análisis fallidos; una falla puede dejar evidencia parcial asociada al análisis fallido.
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
- **RF14.** Permitir aprobar o retirar la inclusión documental de un elemento `UNKNOWN` antes de generar el manual, sin ejecutar el elemento ni ampliar el crawling.
- **RF15.** Incluir en el manual solo elementos `SAFE` o `UNKNOWN` aprobados, con instrucciones funcionales y sin selectores ni lenguaje técnico de clasificación.
- **RF16.** Eliminar el borrador existente al cambiar una aprobación de inclusión documental, para exigir una nueva generación sin contenido obsoleto.
- **RF17.** Conservar en el navegador únicamente la última aplicación seleccionada y las preferencias de idioma; la evidencia y los documentos deben permanecer en el backend.
- **RF18.** Normalizar las rutas excluidas como prefijos absolutos, quitar barras finales salvo en `/`, eliminar duplicados preservando el primer orden y rechazar consultas, fragmentos, escapes porcentuales, más de 50 rutas o rutas de más de 200 caracteres.

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
| `cd backend && ./mvnw test` | Los informes Surefire presentes registran 89 pruebas, 0 fallos, 0 errores y 0 omitidas; no se ejecutó en esta actualización documental. |
| `cd frontend && npm test` | Hay 36 casos `it` declarados; no se ejecutaron en esta actualización documental. |
| `cd frontend && npm run build` | El script está definido; no se ejecutó en esta actualización documental. |
| `git diff --check` | Debe ejecutarse para validar formato; no se afirma un resultado previo. |
| Fixture/E2E y validación manual/browser | Requieren el stack local; no se afirma una ejecución durante esta actualización documental. |

## 8. Evolución futura

IA, workflows, captura guiada de operaciones mutantes, exportación DOCX/PDF, soporte de producción, ejecución asíncrona y almacenamiento remoto se evaluarán después del MVP.
