# Ticketing Mundial 2026

API y aplicacion web desarrolladas para el obligatorio de Base de Datos II.
El sistema cubre la comercializacion, emision, transferencia y validacion de
entradas, ademas del alta administrativa de eventos vendibles.

La implementacion usa Spring Boot con JDBC y SQL explicito sobre la instancia
MySQL compartida por la catedra. Firebase Authentication identifica a los
usuarios y Firebase Admin verifica los ID tokens en el backend.

## Stack

- Java 21.
- Spring Boot 4.0.6.
- Maven Wrapper.
- JDBC explicito, sin ORM.
- MySQL 8, schema compartido `IC_Grupo6`.
- Firebase Authentication y Firebase Admin SDK.
- HTML, CSS y JavaScript sin framework frontend.

## Requisitos

- Java 21 disponible en `PATH`.
- Acceso a la instancia MySQL de la catedra.
- Un proyecto Firebase configurado.
- El archivo privado `service-account.json` de Firebase Admin, fuera del repo.
- Un secreto HMAC de al menos 32 bytes para los codigos QR.

## Variables de entorno

La aplicacion no contiene credenciales privadas en el codigo. Antes de
levantarla deben definirse:

- `DB_URL`: URL JDBC de MySQL.
- `DB_USER`: usuario de la base.
- `DB_PASSWORD`: contrasena de la base.
- `GOOGLE_APPLICATION_CREDENTIALS`: ruta absoluta al
  `service-account.json`.
- `QR_SIGNING_SECRET`: secreto HMAC de al menos 32 bytes.

Ejemplo en PowerShell, usando valores propios:

```powershell
$env:DB_URL="jdbc:mysql://<host>:<port>/IC_Grupo6?sslMode=REQUIRED"
$env:DB_USER="<usuario>"
$env:DB_PASSWORD="<contrasena>"
$env:GOOGLE_APPLICATION_CREDENTIALS="C:\ruta\privada\service-account.json"
$env:QR_SIGNING_SECRET="<secreto-de-al-menos-32-bytes>"
```

No se deben subir al repositorio `service-account.json`, archivos `.env`,
claves privadas ni credenciales de MySQL.

## Ejecucion

Desde la raiz de `ticketing-api`, en Windows:

```powershell
.\mvnw.cmd clean compile
.\mvnw.cmd spring-boot:run
```

En Linux o macOS:

```bash
./mvnw clean compile
./mvnw spring-boot:run
```

La aplicacion queda disponible en:

```text
http://localhost:8080/
```

## Vistas

| URL | Uso |
| --- | --- |
| `/` | Cartelera, autenticacion y accesos segun rol. |
| `/registro.html` | Registro del usuario general. |
| `/compra.html` | Reserva, venta y pago simulado. |
| `/mis-entradas.html` | Entradas propias, transferencias y QR dinamico. |
| `/administracion.html` | Alta de eventos y sectores habilitados. |
| `/validacion.html` | Validacion de ingreso por un funcionario autorizado. |
| `/desarrollo.html` | Panel tecnico para probar endpoints y respuestas crudas. |

El ingreso con Google esta disponible desde la portada. El proveedor Microsoft
esta configurado como acceso institucional UCU de inquilino unico, pero requiere
el consentimiento administrativo del tenant de la Universidad para completar
el login.

## API principal

Todos los endpoints protegidos esperan:

```http
Authorization: Bearer <FIREBASE_ID_TOKEN>
```

### Salud, catalogos e identidad

- `GET /app/health`
- `GET /db/health`
- `GET /paises`
- `GET /tipos-documento`
- `GET /cartelera`
- `GET /auth/firebase/me`
- `POST /auth/resolve`
- `POST /auth/register`

### Venta

- `GET /ventas/catalogo`
- `GET /ventas/catalogo/{idEvento}`
- `POST /ventas`
- `POST /ventas/{idVenta}/pagar`

El pago es simulado. Una aprobacion emite las entradas y sus movimientos
iniciales de titularidad dentro de la misma transaccion.

### Entradas y transferencias

- `GET /entradas/mias`
- `GET /transferencias/recibidas`
- `GET /transferencias/enviadas`
- `POST /entradas/{idEntrada}/transferencias`
- `POST /entradas/{idEntrada}/transferencias/{nroMovimiento}/aceptar`
- `POST /entradas/{idEntrada}/transferencias/{nroMovimiento}/rechazar`
- `POST /entradas/{idEntrada}/transferencias/{nroMovimiento}/cancelar`

La aceptacion realiza el cambio de titularidad atomicamente. Rechazar y
cancelar son terminales no efectivos: cierran la solicitud sin cambiar al
titular vigente.

### QR y validacion

- `GET /entradas/{idEntrada}/qr`
- `GET /validacion/contexto`
- `POST /validaciones/ingreso`

El QR se deriva de una credencial persistida y rota cada 30 segundos mediante
HMAC-SHA256. La ventana temporal usa el reloj de MySQL. Una validacion aceptada
registra la lectura y cambia `ENTRADA.estado_entrada` a `CONSUMIDA` en una
sola transaccion. El consumo es irreversible.

### Administracion

- `GET /admin/contexto`
- `GET /admin/eventos/opciones`
- `POST /admin/eventos`

El alta administrativa crea `EVENTO` y sus filas `EVENTO_SECTOR` de forma
atomica, validando rol, jurisdiccion, fechas, estadio, sectores y capacidades.

## Seed del validador

El archivo [`sql/seed-validacion-demo.sql`](sql/seed-validacion-demo.sql)
prepara el funcionario, dispositivo, vinculacion y asignacion usados en la
demostracion.

Es un seed de una sola ejecucion y supone estos anclajes ya verificados en la
instancia compartida de la catedra:

- `USUARIO_GENERAL.id_usuario = 2`.
- `EVENTO.id_evento = 1`.
- `SECTOR.id_sector = 1`.
- La entrada de prueba pertenece al evento 1 y sector 1.

Para ejecutarlo:

1. Abrir MySQL Shell en modo SQL y conectarse con las credenciales entregadas
   por la catedra.

```powershell
mysqlsh --sql --host=<host> --port=<port> --user=<usuario> --schema=IC_Grupo6 --ssl-mode=REQUIRED -p
```

2. Desde MySQL Shell, cargar el archivo usando su ruta absoluta:

```sql
\source C:/ruta/al/repositorio/ticketing-api/sql/seed-validacion-demo.sql
```

3. Conservar el valor final
   `id_asignacion_dispositivo_validador`. Es el identificador que utiliza el
   endpoint de validacion y que la vista obtiene desde
   `GET /validacion/contexto`.

El seed encadena los identificadores generados mediante `LAST_INSERT_ID()`;
no supone que los `AUTO_INCREMENT` comiencen en 1.

## Prueba rapida del validador

1. El titular abre `mis-entradas.html` y muestra el QR de una entrada
   `EMITIDA`.
2. Para la prueba sin camara, el token crudo puede obtenerse desde
   `desarrollo.html`.
3. El funcionario validador inicia sesion en otra ventana y abre
   `validacion.html`.
4. Pega el token, elige su asignacion y valida.
5. La primera lectura valida queda `ACEPTADA` y la entrada pasa a
   `CONSUMIDA`.
6. Repetir el token produce `ENTRADA_CONSUMIDA`. Un token fuera de su ventana
   produce `QR_VENCIDO`.

## Seguridad y concurrencia

- El backend verifica el ID token Firebase; la interfaz no decide roles.
- Los usuarios se resuelven internamente por `firebase_uid` e
  `id_usuario`.
- Las consultas parametrizan entradas mediante `PreparedStatement`.
- Las operaciones sensibles usan transacciones JDBC y `SELECT ... FOR UPDATE`.
- La emision, transferencia, pago y validacion aplican commit o rollback
  completo.
- Los tokens QR firman entrada, ventana y material secreto de la credencial.
- Los secretos permanecen fuera del repositorio.

La especializacion administrador/validador es parcial y exclusiva en el MER.
El script
[`sql/trigger-exclusividad-roles.sql`](sql/trigger-exclusividad-roles.sql)
implementa esa regla en el motor, pero no esta instalado: el usuario de la
catedra no posee el privilegio `TRIGGER` y MySQL devolvio `ERROR 1142`.
Mientras se obtiene ese permiso, los roles se asignan mediante provision
interna y seeds controlados.

## Limitaciones conocidas

- El acto fisico de escanear se simula pegando el token; la firma, vigencia,
  autorizacion, lectura y consumo son reales.
- El acceso Microsoft/UCU depende del consentimiento administrativo externo.
- El trigger de exclusividad de roles esta documentado pero no activo.
- Las ventas vencidas se normalizan de forma lazy cuando son consultadas o
  procesadas; no existe un job periodico.
- `MODIFICACION_EVENTO` existe como bitacora prevista, pero la edicion de
  eventos no forma parte del ejecutable actual.
- La verificacion agregada de cobertura del funcionario queda como reporte
  futuro.

## Estado del flujo legacy

El flujo anterior con password y BCrypt fue eliminado. No quedan controladores,
endpoints ni dependencias BCrypt en el codigo actual. La autenticacion oficial
pasa por Firebase y los endpoints `/auth/*`.

## Cambios posteriores al informe

Las variaciones entre el informe del 22/06/2026 y este ejecutable se detallan
en [ANEXO_CAMBIOS.md](ANEXO_CAMBIOS.md). Son implementaciones de componentes ya
presentados en el MER V3 y en el modelo fisico, no un cambio de solucion.
