# Contrato de la API - Eyesofter Suite

## Autenticación
### POST /api/login
Permite iniciar sesión validando las credenciales locales contra el archivo de recursos `users.json`.

* **Request Body (JSON):**
```json
{
  "username": "admin",
  "pin": "1234"
}