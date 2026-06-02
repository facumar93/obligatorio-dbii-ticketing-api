\# Ticketing API - Obligatorio DBII



Aplicación mínima para el obligatorio de Base de Datos II.



El proyecto usa:



\- Java 21

\- Spring Boot

\- Maven Wrapper

\- JDBC explícito

\- MySQL cloud provisto por la cátedra

\- Frontend HTML mínimo servido desde Spring Boot



\## Estado actual



La aplicación fue adaptada para conectarse a la base MySQL cloud del grupo:



\- Schema/base: `IC\_Grupo6`

\- Driver JDBC: MySQL Connector/J

\- Variables de entorno:

&#x20; - `DB\_URL`

&#x20; - `DB\_USER`

&#x20; - `DB\_PASSWORD`



El endpoint `/db/health` fue probado correctamente contra MySQL cloud y responde con conexión OK sobre `IC\_Grupo6`.



\## Transición técnica realizada



Originalmente el proyecto venía funcionando contra Db2 local en Ubuntu VirtualBox. Luego se adaptó a MySQL cloud.



Cambios principales:



\- Reemplazo del driver Db2 por `mysql-connector-j` en `pom.xml`.

\- Cambio de `DbConnectionFactory` para usar variables genéricas:

&#x20; - `DB\_URL`

&#x20; - `DB\_USER`

&#x20; - `DB\_PASSWORD`

\- Cambio de `/db/health` para usar SQL compatible con MySQL:

&#x20; - `SELECT 1 AS OK`

\- Revisión del proyecto para eliminar referencias directas a Db2.

\- Ajuste del manejo de errores de duplicado en `UsuarioController`, contemplando:

&#x20; - Db2: SQLSTATE `23505`

&#x20; - MySQL: SQLSTATE `23000` / error code `1062`

\- Actualización de textos del frontend para hablar de “base de datos” en vez de Db2.



\## Endpoints actuales



\- `GET /app/health`

\- `GET /db/health`

\- `GET /paises`

\- `GET /usuarios`

\- `POST /usuarios`



\## Situación actual con MySQL cloud



Se creó correctamente la tabla `PAIS` en `IC\_Grupo6`.



Al intentar crear `PAIS\_SEDE` con clave foránea hacia `PAIS`, MySQL devolvió:



```text

ERROR 1142 (42000): REFERENCES command denied to user 'ic\_g6\_admin'@'...' for table 'IC\_Grupo6.PAIS'

