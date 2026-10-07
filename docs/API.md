# Contrato de la API - Eyesofter Suite

## Autenticación
### POST /api/login
Permite iniciar sesión validando las credenciales locales contra el archivo de recursos `users.json` ubicado en `server/src/main/resources/users.json`.

* **Request Body (JSON):**
```json
{
  "username": "admin",
  "password": "123456"
}