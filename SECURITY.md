# Seguridad — EYESOFTER Suite (servidor de la app)

| Área | Medida |
|---|---|
| Acceso | Mismas cuentas que el portal; bloqueo compartido de 3 intentos; respuestas con el mismo tiempo exista o no la cuenta |
| Dos pasos | TOTP compatible con el portal; obligatorio para el personal (`EXIGIR_2FA_PERSONAL`) |
| Tokens | JWT firmados; el personal 12 h, tutores 30 días; se invalidan al cambiar contraseña, rol o desactivar la cuenta |
| Escritorio | Sin clave compartida: cada equipo se vincula en el navegador con una cuenta de su escuela (con código de dos pasos) |
| Varias escuelas | `Usuario.alcance`: el personal solo ve su escuela; alertas solo a la Enfermería de la escuela del alumno |
| Datos | SQL parametrizado; datos de salud y archivos cifrados (AES-256-GCM); búsqueda de alumnos sin listado completo |
| Red | HSTS, `Cache-Control: no-store`, `nosniff`, límite de peticiones por IP |
| Pruebas | `SeguridadTest`, `MultiescuelaTest`, `EscritorioTest` cubren estos controles |

## Reportar una vulnerabilidad

Si encuentras una falla de seguridad, **no abras un issue público**. Usa
*Security → Report a vulnerability* de este repositorio (reporte privado de GitHub) o el formulario
*Reportar una falla* del portal. Responderemos lo antes posible.

## Datos y secretos

- **Todos los datos de este repositorio son ficticios** (nombres, CURP, teléfonos y correos de ejemplo).
  Ningún dato real de alumnos o familias se guarda en el código.
- **No hay secretos en el repositorio.** Llaves de cifrado, secretos de sesión, contraseñas y conexiones
  a la base viven solo en las variables de entorno de Railway.
- Las cuentas demo (contraseña `Eyesofter123!`) existen solo en desarrollo. En producción el portal
  desactiva al arrancar cualquier cuenta demo que conserve esa contraseña.

