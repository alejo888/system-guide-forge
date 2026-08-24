# SystemGuideForge
## Especificación funcional del MVP

## 1. Objetivo

Permitir que una persona documente un sistema web local recorriéndolo de forma segura y de solo lectura, conserve evidencia de sus módulos, pantallas y elementos, y obtenga un borrador editable de manual.

## 2. Alcance

### Incluye

- Una instancia de uso personal.
- Sistemas web locales.
- Registro y configuración de una aplicación.
- URL base y URL de login.
- Login tradicional mediante formulario de usuario y contraseña.
- Prueba de acceso y autenticación.
- Análisis automático seguro y de solo lectura.
- Detección de módulos, pantallas y elementos relevantes.
- Screenshots asociados a pantallas, sin credenciales ni secretos.
- Bloqueo por defecto de acciones mutantes o desconocidas.
- Revisión y edición manual de los resultados.
- Generación de un borrador editable de manual.

### No incluye

- Sistemas de producción o accesibles fuera del entorno local.
- SSO, OAuth o MFA.
- Acciones que creen, modifiquen, eliminen, envíen, aprueben, rechacen o procesen datos.
- Workflows, captura guiada de operaciones mutantes o validación automática de funcionalidades.
- IA como requisito del análisis o de la generación del borrador.
- Exportación DOCX, PDF, colaboración, multiusuario, roles, multi-tenant o versionado avanzado.

## 3. Flujo principal

```text
Registrar sistema
      ↓
Configurar acceso
      ↓
Probar acceso y autenticarse
      ↓
Ejecutar análisis seguro
      ↓
Revisar módulos, pantallas y elementos
      ↓
Editar resultados
      ↓
Generar borrador editable de manual
      ↓
Editar manual
```

## 4. Módulos funcionales

### Sistemas

- Crear y editar la configuración de un sistema local.
- Guardar URL base, URL de login y credenciales protegidas.
- Probar conectividad y acceso.

### Análisis

- Iniciar un análisis, consultar su estado y conservar resultados parciales.
- Mantener como límite inicial `MAX_ACTIVE_ANALYSES = 1`.
- Recorrer únicamente navegación y controles clasificados como seguros.
- Bloquear acciones mutantes o cuyo riesgo no pueda determinarse.

### Evidencia

Por cada pantalla se conserva, cuando esté disponible:

- URL y ruta;
- título y nombre inferido;
- módulo estimado;
- elementos detectados;
- screenshot sanitizado.

### Manual

- Crear un borrador a partir de pantallas y elementos seleccionados.
- Editar título, secciones, orden, texto y screenshots.
- Mantener la relación entre cada sección y su evidencia.

## 5. Requisitos funcionales

- **RF01.** Registrar y editar un sistema web local.
- **RF02.** Configurar URL base, URL de login y credenciales de prueba.
- **RF03.** Probar conectividad y autenticación tradicional.
- **RF04.** Iniciar un análisis automático de solo lectura.
- **RF05.** Detectar módulos, pantallas y elementos relevantes.
- **RF06.** Tomar screenshots sin credenciales ni secretos.
- **RF07.** Clasificar acciones como `SAFE`, `MUTATING` o `UNKNOWN`.
- **RF08.** Ejecutar automáticamente solo acciones `SAFE` y bloquear las demás.
- **RF09.** Consultar y revisar los resultados del análisis.
- **RF10.** Generar y editar un borrador de manual.

## 6. Requisitos no funcionales

- **RNF01 Seguridad.** Las credenciales se almacenan cifradas y nunca se escriben en logs.
- **RNF02 Privacidad.** Las credenciales no aparecen en screenshots ni se envían a prompts de IA.
- **RNF03 Solo lectura.** El análisis no debe mutar datos del sistema objetivo.
- **RNF04 Trazabilidad.** El manual debe conservar referencias a la evidencia de origen.
- **RNF05 Recuperación.** Los resultados parciales deben conservarse si el análisis falla.
- **RNF06 Simplicidad.** El MVP debe funcionar con infraestructura local mínima.

## 7. Criterio de aceptación

El MVP es funcional cuando, contra un sistema web local con login tradicional, permite probar el acceso, autenticarse, recorrer pantallas sin mutar datos, detectar elementos, guardar screenshots sanitizados y producir un borrador de manual que la persona pueda editar.

## 8. Evolución futura

IA, workflows, captura guiada de operaciones mutantes, DOCX y soporte de producción se evaluarán después del MVP y no deben condicionar su implementación inicial.
