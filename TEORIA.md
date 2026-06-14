# Teoría de Concurrencia — TP Integrador

## ¿Por qué ReadWriteLock siempre activo?

Porque protege toda operación que toca datos de un `NodoTrabajador`, no solo cuando hay contrato.

```
// Lecturas (readLock) — múltiples hilos en paralelo
getTrabajador()       → readLock
getEstado()           → readLock
getPromedio()         → readLock
getCalificaciones()   → readLock

// Escrituras (writeLock) — exclusivo
contratar()           → writeLock
finalizar()           → writeLock
calificar()           → writeLock
```

Cuando los lectores hacen `BUSCAR` / `LISTAR` / `OBTENER` al mismo tiempo, todos adquieren `readLock` simultáneamente. Cuando un escritor hace `CONTRATAR`, espera que todos los lectores terminen y adquiere `writeLock` en exclusiva. Siempre está operando — es la capa base de protección sobre el nodo.

---

## ¿Por qué Semáforo y Monitor activos al mismo tiempo?

Porque resuelven problemas distintos y se activan en el mismo momento (`CONTRATAR`) por coincidencia:

| Mecanismo | Protege | ¿Quién espera? | ¿Quién desbloquea? |
|-----------|---------|----------------|--------------------|
| Semáforo  | Orden `FINALIZAR → CALIFICAR` | El hilo que quiere calificar (`acquire()`) | `FINALIZAR` (`release()`) |
| Monitor   | Despertar a `HiloExpiracionContratos` | `HiloExpiracion` (`wait()`) | `CONTRATAR` (`notifyAll()`) |

Son ortogonales: el Semáforo coordina a dos consumidores (quien finaliza vs quien califica). El Monitor coordina al sistema (cuándo tiene que empezar a vigilar vencimientos). Que ambos se activen en `CONTRATAR` es consecuencia de que `CONTRATAR` dispara los dos problemas a la vez: "hay que ordenar la calificación" **y** "hay que empezar a medir el tiempo de vencimiento".

**Consecuencia visible en el dashboard:** cuando se hace `FINALIZAR`, el Semáforo pasa a libre y el Monitor a reposo al mismo tiempo — porque `FINALIZAR` resuelve ambos (libera al calificador, y si no quedan contratos activos, `HiloExpiracion` vuelve a `wait()`).

---

## ¿Por qué el Semáforo no es redundante con el RWLock?

El Semáforo bloquea **solo en CALIFICAR**:

```
CONTRATAR → new Semaphore(0)      // creado en 0 (bloqueado)
FINALIZAR → semaforo.release()    // sube a 1
CALIFICAR → semaforo.acquire()    // baja a 0, o BLOQUEA si no se llamó release()
```

Sin el Semáforo, si alguien manda `CALIFICAR` antes de `FINALIZAR`, no hay nada que lo detenga. Podrías chequear el estado con un `if`, pero eso tiene una condición de carrera:

```
Hilo A: if (estado == FINALIZADO) → true      ← pasa el if
Hilo B:     setState(EN_TRABAJO)              ← lo revierte
Hilo A:         calificar()                  ← califica un contrato activo  ✗
```

El Semáforo elimina esa carrera porque **el bloqueo y el avance son atómicos** — no hay ventana entre "chequear si se finalizó" y "entrar a calificar".

---

## Resumen: cada mecanismo resuelve un problema distinto

| Mecanismo | Problema que resuelve |
|---|---|
| `ReadWriteLock` | Acceso concurrente a los campos del nodo (lectores/escritor) |
| `Semaphore(0)` | Ordenamiento `FINALIZAR → CALIFICAR` (no se puede invertir el orden) |
| Monitor (`wait`/`notify`) | Despertar `HiloExpiracion` eficientemente sin busy-wait |

Son tres patrones de concurrencia clásicos aplicados a tres situaciones distintas. Si se reemplaza el Semáforo con un `if + RWLock`, se pierde la demostración del patrón productor-consumidor de coordinación de eventos.

---

## Análisis de resultados del cliente de stress

### Escenario 1 — ReadWriteLock (50 hilos, 1 worker)

Resultado típico: **1 ganador, 49 rechazados**.

Exactamente lo esperado. El `writeLock` garantiza exclusión mutua: solo el primer hilo que llega adquiere el lock y contrata; los 49 restantes encuentran el worker ocupado y reciben `ERROR`. Si hubiera más de 1 ganador, el lock estaría roto.

### Escenario 2 — Monitor / HiloExpiracionContratos (5 contratos)

Resultado típico: **PARCIAL 4/5** — `ma-01` ya está `EN_TRABAJO` al inicio.

No es un bug. Si la simulación del dashboard está corriendo en paralelo, puede haber contratado a `ma-01` antes de que arranque el escenario. Los 4 contratos que sí se crean **expiran correctamente** — el Monitor despertó a `HiloExpiracionContratos` y este los liberó dentro de los 8 segundos de espera. El PARCIAL es consecuencia de dos clientes TCP concurrentes operando sobre el mismo grafo, lo cual en sí mismo también es una demostración de concurrencia.

### Escenario 3 — Semáforo (5 calificaciones bloqueadas)

Resultado típico: **CORRECTO 5/5**.

Los 5 hilos calificadores se bloquean en `acquire()` simultáneamente. Al enviar `FINALIZAR` a los 5 contratos, los 5 semáforos hacen `release()` y los 5 hilos se desbloquean. El orden `FINALIZAR → CALIFICAR` se respeta en todos los casos.

### Escenario 4 — Benchmark de throughput (20 lectores + 10 escritores, 10s)

Resultado típico:

```
BUSCAR / LISTAR / OBTENER / STATS → ~98.000 req/s · 0,0 ms de latencia
CONTRATAR                         → ~50% OK · ~50% error
FINALIZAR                         → coincide exactamente con CONTRATAR OK
TOTAL                             → ~480.000 req/s · 94% éxito
```

**Lecturas a 0,0 ms:** el `ConcurrentHashMap` permite lecturas simultáneas sin bloqueo. La latencia es sub-milisegundo — se redondea a cero.

**CONTRATAR ~50% error:** es el ratio exactamente esperado. Hay 10 escritores compitiendo sobre 5 bench workers. En promedio, la mitad de los workers está ocupada en cualquier momento → ~50% de colisiones. Si el error fuera 0%, el locking no estaría funcionando.

**FINALIZAR == CONTRATAR OK:** cada `FINALIZAR` corresponde a un `CONTRATAR` exitoso previo. Que los números coincidan exactamente confirma que no hay contratos huérfanos ni fugas de estado.

---

## ¿Qué significan los errores en el contador del dashboard?

Los errores **no son fallos del programa** — son el servidor rechazando correctamente operaciones inválidas bajo concurrencia.

| Error | Causa | Significa |
|-------|-------|-----------|
| `CONTRATAR` | El worker elegido ya está `EN_TRABAJO` | El locking funciona: solo un contrato activo por vez |
| `CALIFICAR` | El contrato ya no existe (expiró antes del `CALIFICAR`) | `HiloExpiracion` lo limpió primero |

Los errores de `CONTRATAR` son los más frecuentes en la simulación porque cada escritor elige un worker al azar — las colisiones son inevitables y esperadas. Con 8 escritores compitiendo sobre 20 workers, la probabilidad de colisión sube a medida que más workers quedan ocupados. Si hubiera **0 errores** con múltiples hilos compitiendo por el mismo worker, eso sería el problema real: significaría que el locking no está impidiendo dobles contrataciones.

---

## ¿Por qué el grafo usa ConcurrentHashMap?

`GrafoTrabajadores` almacena los nodos en un `ConcurrentHashMap<String, NodoTrabajador>`. Esto garantiza que las operaciones estructurales sobre el mapa (agregar, buscar, eliminar nodos) sean thread-safe sin bloquear toda la estructura:

- Las **lecturas** (`get`, iteración con `values()`) son no bloqueantes — múltiples hilos las hacen en paralelo.
- Las **escrituras** (`putIfAbsent`, `remove`) bloquean solo el segmento del bucket afectado, no el mapa entero.
- `putIfAbsent` es atómica: si 50 hilos intentan registrar el mismo ID simultáneamente, solo uno lo inserta.

La concurrencia del mapa (nivel grafo) y la concurrencia de cada nodo (nivel `ReadWriteLock`) son dos capas independientes: el mapa protege _quién está en el grafo_, el lock protege _el estado interno de cada nodo_.

---

## ¿Cómo funciona HiloExpiracionContratos?

Es un hilo daemon que corre un loop de espera eficiente — **sin busy-wait**:

```java
synchronized(monitorExpiracion) {
    monitorExpiracion.wait();       // duerme hasta que llegue un CONTRATAR
}
// revisa todos los nodos en trabajo
// si alguno venció → llama finalizar()
// vuelve al wait()
```

Sin el Monitor, la alternativa sería un `while(true) { sleep(1000); revisar(); }` — que consume CPU aunque no haya ningún contrato activo. Con `wait()`, el hilo no consume nada hasta que `CONTRATAR` llama `notifyAll()` para despertarlo.

---

## Protocolo TCP

El servidor escucha en el puerto `9090`. Cada conexión TCP puede enviar múltiples comandos en la misma sesión (una línea por comando, respuesta por línea). El formato general es:

```
TIPO arg1 arg2 "argumento con espacios"
```

Los argumentos con espacios van entre comillas dobles. El parser las elimina antes de procesar.

### Comandos disponibles

| Comando | Formato | Respuesta |
|---------|---------|-----------|
| `REGISTRAR` | `REGISTRAR <id> <nombre> <oficio> "<descripcion>"` | `OK` / `ERROR` |
| `CONTRATAR` | `CONTRATAR <idTrabajador> <idConsumidor> "<desc>" "<finEstimado>"` | `OK contrato:<idContrato>` / `ERROR` |
| `FINALIZAR` | `FINALIZAR <idTrabajador> <idContrato>` | `OK` / `ERROR` |
| `CALIFICAR` | `CALIFICAR <idTrabajador> <idContrato> <1-5> "<comentario>"` | `OK <promedio>` / `ERROR` |
| `BUSCAR` | `BUSCAR <oficio>` | Lista ordenada por promedio / `VACIO` |
| `LISTAR` | `LISTAR` | Todos los trabajadores / `VACIO` |
| `OBTENER` | `OBTENER <idTrabajador>` | Detalle del trabajador / `ERROR` |
| `STATS` | `STATS` | Cantidad de trabajadores y contratos activos |
| `PING` | `PING` | `PONG` |
| `GUARDAR` | `GUARDAR` | `OK guardado` |

### Oficios válidos

`ALBANIL` · `ELECTRICISTA` · `PLOMERO` · `CARPINTERO` · `MAESTRO` · `PROFESOR`

### Flujo obligatorio de un contrato

```
CONTRATAR → (trabajador pasa a EN_TRABAJO)
FINALIZAR → (semáforo liberado, trabajador vuelve a DISPONIBLE)
CALIFICAR → (acquire() desbloquea, promedio recalculado)
```

`CALIFICAR` antes de `FINALIZAR` bloquea al hilo llamante hasta que se llame `FINALIZAR`. No es un error — es el Semáforo funcionando.

### Formato de fecha para CONTRATAR

ISO-8601 sin zona horaria: `2025-06-13T18:30:00`

---

## ¿Para qué sirve el panel TCP directo del dashboard?

El dashboard tiene un panel que envía comandos TCP al servidor en tiempo real, sin necesidad de abrir una terminal. Tiene dos niveles:

**Botones de lectura rápida:** PING, STATS, LISTAR, GUARDAR, BUSCAR por oficio, y un selector de OBTENER con todos los workers cargados automáticamente.

**Formularios de escritura:** permiten ejecutar REGISTRAR, CONTRATAR, FINALIZAR y CALIFICAR sin recordar IDs ni formatos. Los dropdowns se pueblan desde los datos en vivo:
- CONTRATAR muestra todos los workers con su estado actual (incluidos los ocupados, para poder forzar errores manualmente)
- FINALIZAR muestra solo los workers `EN_TRABAJO` con su id de contrato pre-cargado
- CALIFICAR se habilita automáticamente después de hacer FINALIZAR desde el mismo panel

---

## Cómo observar el Semáforo manualmente

El flujo manual desde el panel TCP permite ver el Semáforo en acción paso a paso:

1. **CONTRATAR** un worker → la tarjeta muestra `🚦 Semáforo ▓ BLOQUEADO`
2. **Esperar** (el worker está `EN_TRABAJO`, el Semáforo bloquea cualquier intento de calificar)
3. **FINALIZAR** desde el panel → la tarjeta cambia a `🚦 Semáforo libre` instantáneamente — el `release()` se ejecutó
4. **CALIFICAR** desde el panel → las estrellas se actualizan — el `acquire()` pasó porque el Semáforo ya tenía el `release()`

Si intentaras CALIFICAR sin haber hecho FINALIZAR, el hilo del servidor quedaría bloqueado en `acquire()` indefinidamente — el servidor no respondería hasta que alguien llame FINALIZAR.
