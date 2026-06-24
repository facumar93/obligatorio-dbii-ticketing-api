# Anexo de cambios entre informe y ejecutable

## Alcance

Este anexo registra las variaciones realizadas entre la entrega del informe
del **22/06/2026** y la entrega del ejecutable del **24/06/2026**.

Los cambios completan componentes ya presentados en el MER V3, el modelo
logico y el modelo fisico. No sustituyen la solucion elegida ni introducen un
modelo conceptual diferente.

## Cambios incorporados

### 1. QR dinamico de la entrada

Se implemento el motor de credenciales y tokens temporales que estaba
representado por `CREDENCIAL_QR`:

- Creacion lazy de la credencial para entradas emitidas.
- Semilla y nonce generados con `SecureRandom`.
- Firma HMAC-SHA256 con secreto externo en `QR_SIGNING_SECRET`.
- Token asociado a la entrada y a una ventana de 30 segundos.
- Reloj autoritativo de MySQL.
- Imagen QR y renovacion automatica en `mis-entradas.html`.
- Token crudo disponible solamente en `desarrollo.html` para pruebas.

### 2. Validacion de ingreso

Se completo el frente del funcionario de validacion:

- `GET /validacion/contexto` comprueba el rol y devuelve sus asignaciones
  activas.
- `POST /validaciones/ingreso` verifica token, credencial, entrada,
  dispositivo, vinculacion, horario, evento y sector.
- La entrada se bloquea mediante `FOR UPDATE`.
- Una lectura aceptada registra
  `LECTURA_DE_VALIDACION_INGRESO` y cambia la entrada a `CONSUMIDA` dentro
  de la misma transaccion.
- Los rechazos identificables quedan auditados con su motivo.
- Se agrego `validacion.html`, donde el escaneo fisico se simula pegando el
  token. La validacion de negocio no se simula.
- Se agrego `sql/seed-validacion-demo.sql` para preparar el funcionario,
  dispositivo, vinculacion y asignacion de la demostracion.

### 3. Acceso del validador desde la portada

La portada consulta `GET /validacion/contexto` despues de resolver la
identidad. Si el backend confirma el rol, muestra:

- El distintivo naranja `Validador`.
- El boton `Validar ingresos`, dirigido a `validacion.html`.

Un usuario sin el rol recibe `403 USUARIO_NO_ES_VALIDADOR`; la portada
mantiene oculto el acceso.

### 4. Bitacora de modificaciones de eventos

Se creo fisicamente la tabla `MODIFICACION_EVENTO`, ya incorporada al MER V3
y al modelo fisico. La tabla conserva la estructura prevista para identificar
evento, asignacion administrativa actuante, fecha y alcance de la
intervencion.

La edicion de eventos y la escritura de esta bitacora no se incorporaron al
ejecutable actual.

### 5. Exclusividad administrador/validador

La especializacion de `USUARIO_GENERAL` en administrador o validador fue
definida como parcial y exclusiva `(p,e)`.

Se preparo `sql/trigger-exclusividad-roles.sql` para impedir que un mismo
usuario sea insertado en ambos subtipos. Al intentar instalarlo, MySQL
respondio:

```text
ERROR 1142: TRIGGER command denied
```

El usuario de la catedra no posee el privilegio `TRIGGER`; por tanto, ningun
trigger fue creado y la base permanecio intacta. Hasta obtener el permiso, la
regla se sostiene mediante provision interna y seeds revisados.

## Trazabilidad

| Componente | Estado en el informe | Estado en el ejecutable |
| --- | --- | --- |
| QR dinamico | Modelado en MER V3 y DDL | Generacion, visualizacion y rotacion implementadas |
| Credencial QR | Tabla definida | Creacion lazy e integridad HMAC implementadas |
| Validacion de ingreso | Modelo y tablas definidos | Endpoint transaccional y vista implementados |
| Dispositivo y asignacion | Estructura persistente definida | Seed de demostracion agregado |
| Consumo de entrada | Estado previsto | Cambio irreversible y lectura atomica implementados |
| Acceso del validador | Rol modelado | Distintivo y navegacion desde portada |
| `MODIFICACION_EVENTO` | Incorporada al modelo final | Tabla creada; escritura futura |
| Exclusividad de roles | Restriccion conceptual `(p,e)` | Script preparado; instalacion bloqueada por privilegios |

## Declaracion final

Las diferencias responden al plazo expresamente previsto entre la entrega del
informe y la entrega del ejecutable. Se implementaron piezas ya documentadas,
manteniendo las entidades, relaciones, restricciones y decisiones centrales
de la solucion presentada.
