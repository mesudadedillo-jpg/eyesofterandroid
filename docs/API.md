# Contrato de la API - Eyesofter Suite

## Autenticación
### POST /api/login
Permite iniciar sesión validando las credenciales locales contra el archivo de recursos `users.json`.

* **Request Body (JSON):**
```json
{
  "username": "admin",
  "password": "123456"
}
```

* **Response Body (`200 OK` - Exitoso):**
```json
{
  "exito": true,
  "mensaje": "Inicio de sesión exitoso",
  "username": "admin",
  "fullName": "Administrador del Sistema",
  "role": "ADMINISTRADOR"
}
```

* **Response Body (`401 Unauthorized` - Error):**
```json
{
  "exito": false,
  "mensaje": "Credenciales incorrectas",
  "username": null,
  "fullName": null,
  "role": null
}
```

## Alumnos / Tamizaje
### GET /api/alumnos/{id}/resultado
Obtiene el resultado del tamizaje de un alumno por su ID.
