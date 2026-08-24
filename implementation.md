# SystemGuideForge
## Implementación del MVP

## 1. Flujo técnico

### Registrar y configurar un sistema

1. Crear `PROJECT` y `TARGET_APPLICATION`.
2. Validar que la URL pertenezca a un sistema local autorizado.
3. Guardar URL base, URL de login, rutas excluidas y credenciales cifradas.
4. No incluir credenciales en respuestas, logs ni objetos de análisis.

### Probar acceso

1. Validar protocolo, host y redirecciones permitidas.
2. Abrir un contexto aislado de Playwright.
3. Navegar a la URL de login.
4. Completar el formulario tradicional con los secretos en memoria.
5. Ejecutar el envío y verificar el resultado.
6. Cerrar y limpiar el contexto si la prueba falla.

### Iniciar análisis

1. Verificar que no exista otro análisis activo.
2. Crear `ANALYSIS` con estado `RUNNING`.
3. Crear un contexto aislado y autenticado.
4. Recorrer la pantalla inicial y las rutas permitidas.
5. Guardar resultados parciales después de cada pantalla.
6. Finalizar como `COMPLETED` o `FAILED` sin exponer secretos.

### Analizar una pantalla

1. Obtener URL, ruta y título.
2. Detectar módulo por configuración o heurística simple.
3. Extraer elementos relevantes.
4. Clasificar cada acción como `SAFE`, `MUTATING` o `UNKNOWN`.
5. Guardar `PAGE` y `UI_ELEMENT`.
6. Ocultar o excluir valores sensibles antes de tomar y persistir el screenshot.

### Recorrer de forma segura

Mantener dos colecciones:

```text
visited
pending
```

Por cada control:

1. Ejecutar solo si su clasificación es `SAFE`.
2. Esperar la navegación o el cambio de estado.
3. Extraer la nueva evidencia.
4. Calcular fingerprint.
5. Guardar la pantalla si es nueva.
6. Detenerse ante una acción mutante o desconocida.

### Generar el borrador

1. Seleccionar pantallas y elementos desde la evidencia.
2. Crear `DOCUMENT` y sus `DOCUMENT_SECTION`.
3. Generar texto descriptivo basado únicamente en la evidencia observada.
4. Asociar screenshots sanitizados.
5. Permitir edición de título, contenido, orden y asociaciones.

La generación inicial es determinista y no requiere IA.

## 2. Política de acciones

### Permitidas automáticamente

- Abrir menús, submenús, tabs, acordeones o modales.
- Navegar enlaces internos permitidos.
- Paginar sin modificar datos.
- Abrir detalles en modo consulta.

### Bloqueadas

- Crear, guardar, editar o eliminar.
- Aprobar, rechazar, confirmar, enviar o procesar.
- Pagar, importar, exportar o descargar cuando el efecto no sea claramente de solo lectura.
- Cualquier acción que cambie estado del sistema.

### Desconocidas

Se bloquean por defecto y se informan para revisión manual. El análisis nunca intenta adivinar el efecto de un control.

## 3. Protección de credenciales

- Cifrar las credenciales en reposo.
- Mantener secretos descifrados solo durante la autenticación.
- No escribir credenciales, cookies, tokens ni headers sensibles en logs.
- No incluir secretos en screenshots, evidencia, respuestas API ni prompts.
- Limpiar el contexto del navegador al finalizar cada operación.

## 4. Seguridad de navegación

- Aceptar únicamente sistemas locales configurados por la persona.
- Validar protocolos, hosts y redirecciones.
- Rechazar destinos no autorizados y acceso a servicios de metadata.
- Aplicar timeouts y límites de profundidad.
- Mantener `MAX_ACTIVE_ANALYSES = 1`.

## 5. Contratos principales

```java
public interface BrowserAnalyzer {
    AnalysisResult analyze(AnalysisRequest request);
}

public interface FileStorage {
    String save(byte[] content, String path);
    byte[] read(String path);
}
```

Los contratos se mantienen enfocados en análisis, evidencia y documentación editable. No se agregan contratos de IA, workflows ni DOCX al MVP.

## 6. API

REST es el contrato entre Angular y Spring Boot. `openapi.yaml` define únicamente operaciones para:

- proyectos y sistemas;
- prueba de acceso;
- análisis y pantallas;
- consulta de elementos y screenshots;
- creación y edición del borrador de manual.

No se publican endpoints de workflows, IA ni exportación DOCX.

## 7. Pruebas mínimas

### Unitarias

- normalización y validación de URL local;
- fingerprint;
- clasificación de riesgo;
- sanitización de screenshots y evidencia;
- protección de credenciales.

### Integración

- PostgreSQL y Flyway;
- filesystem local;
- Playwright con login tradicional;
- bloqueo de acciones mutantes y desconocidas.

### E2E

Probar un sistema local con login, SPA, menús, tabs, modales, tablas y navegación segura, verificando que ninguna operación cambie datos.

## 8. Evolución posterior

IA, workflows, captura guiada, DOCX y soporte de producción requieren decisiones y controles adicionales. Se mantienen fuera de la implementación inmediata.
