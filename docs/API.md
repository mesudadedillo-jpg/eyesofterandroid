# Contrato de la API - Eyesofter Suite

## Servidor en Producción (Railway)
- **URL Base:** `https://eyesofterandroid-production.up.railway.app`

---

## Endpoints

### 1. Estado de Salud
`GET /salud`
Verifica la disponibilidad del servidor y de la base de datos MySQL.
* **Respuesta 200 OK:**
```json
{
  "status": "ok",
  "db": true
}
```

### 2. Autenticación
`POST /api/login`
Inicia sesión validando credenciales de usuario.

* **Request Body (JSON):**
```json
{
  "username": "admin",
  "password": "123456"
}
```
* **Respuesta 200 OK:**
```json
{
  "token": "jwt-token-ejemplo",
  "user": {
    "username": "admin",
    "fullName": "Administrador del Sistema",
    "role": "ADMINISTRADOR"
  }
}
```

### 3. Ópticas Cercanas
`GET /opticas/cercanas?lat=19.432608&lng=-99.133209` o `GET /opticas/cercanas?query=CDMX`
Obtiene las ópticas cercanas ordenadas por distancia mediante la fórmula de Haversine o filtra por ciudad/código postal.
