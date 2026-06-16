# Ticketing API - Obligatorio DBII

Sistema de ticketing para el Mundial 2026, desarrollado como obligatorio de Base de Datos II.
El objetivo es modelar y soportar la comercializacion, transferencia y validacion de entradas para partidos.

API minima construida con Spring Boot + JDBC explicito contra MySQL cloud, con autenticacion Firebase para identificar usuarios.

## Stack

- Java 21
- Spring Boot
- Maven Wrapper
- JDBC explicito y SQL directo, sin ORM
- MySQL cloud, schema `IC_Grupo6`
- Firebase Authentication + Firebase Admin SDK

## Configuracion requerida

Las credenciales no estan incluidas en el repositorio.

Para correr la aplicacion hay que definir estas variables de entorno:

- `DB_URL`
- `DB_USER`
- `DB_PASSWORD`
- `GOOGLE_APPLICATION_CREDENTIALS`

`GOOGLE_APPLICATION_CREDENTIALS` debe apuntar al archivo `service-account.json` de Firebase Admin SDK. Ese archivo contiene una clave privada y debe mantenerse fuera del repo.

## Como correr

En Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

En Linux/macOS/Git Bash:

```bash
./mvnw spring-boot:run
```

## Endpoints principales

### Salud y catalogos

- `GET /app/health`
- `GET /db/health`
- `GET /paises`
- `GET /tipos-documento`

### Identidad

- `GET /auth/firebase/me`
  Verifica un token Firebase y devuelve la identidad del usuario autenticado.

- `POST /auth/resolve`
  Resuelve la identidad Firebase contra `USUARIO_GENERAL`.
  Puede devolver login correcto, vinculacion de usuario existente o indicar que requiere registro.

- `POST /auth/register`
  Registra un usuario autenticado por Firebase en `USUARIO_GENERAL`.
  La identidad (`firebase_uid`, correo verificado) se toma del token, no del body.

## Frontend minimo

La aplicacion sirve vistas HTML simples desde Spring Boot:

- `index.html`: pruebas de salud, catalogos y autenticacion Firebase.
- `registro.html`: formulario de registro de usuario general.

El registro valida en backend:

- coherencia entre pais y tipo de documento;
- formato basico de CI, DNI y pasaporte;
- digito verificador de cedula uruguaya para `URY + CI`.

## Seguridad

Las claves publicas del Firebase Web SDK visibles en `index.html` y `registro.html` (`apiKey`, `authDomain`, `projectId`, `appId`) son identificadores de cliente, no secretos.

La seguridad real se apoya en:

- verificacion del ID token en backend mediante `FirebaseTokenVerifier`;
- credenciales privadas de Firebase Admin fuera del repo;
- variables de entorno para conexion a base de datos.

No se debe subir `service-account.json`, `.env` ni credenciales de base de datos.

## Estado legacy

El flujo viejo de usuarios con password/BCrypt fue eliminado.

`UsuarioController` y los endpoints `/usuarios` ya no forman parte del flujo actual. La autenticacion oficial pasa por Firebase y los endpoints `/auth/*`.
