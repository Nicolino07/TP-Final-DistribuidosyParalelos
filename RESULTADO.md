# Resultados — Stress Client

Corrida del stress client contra el servidor local (`localhost:9090`).
El servidor corre en un único proceso Java con un grafo de 25 trabajadores en memoria.

---

## Carga inicial

Antes de los escenarios el stress client registra 20 trabajadores de distintos oficios
y carga 25 calificaciones históricas para que el grafo arranque con datos reales.

```
Trabajadores registrados : 20 (+ 5 de benchmark)
Calificaciones históricas: 25
Top albaniles     : al-03:5,0  al-01:4,7  al-05:4,0  al-02:3,5  al-04:2,0
Top electricistas : el-01:5,0  el-04:5,0  el-02:4,0  el-03:3,0
```

---

## Escenario 1 — Doble contratación simultánea

**Mecanismo demostrado: `ReentrantReadWriteLock` por nodo**

50 hilos salen al mismo tiempo a contratar al mismo trabajador (`al-01 / Roberto`).
El `writeLock` garantiza exclusión mutua: solo el primer hilo en adquirirlo gana.

```
Resultado E1 → Ganaron: 1 | Rechazados: 49  (esperado: 1 ganador, 49 rechazados)
```

Sin sincronización, varios hilos creerían haber contratado al mismo trabajador al mismo tiempo.

---

## Escenario 2 — Contratos que expiran automáticamente

**Mecanismo demostrado: Monitor `wait` / `notifyAll`**

Se crean 5 contratos en paralelo con vencimiento en 4 segundos.
El `HiloExpiracionContratos` duerme sobre un monitor y se despierta periódicamente
para revisar vencimientos. A los 8 segundos los 5 trabajadores volvieron a `Disponible`
sin que ningún usuario enviara `FINALIZAR`.

```
Estado antes  → al-02:En trabajo  el-01:En trabajo  pl-01:En trabajo  ca-01:En trabajo  ma-01:En trabajo
Estado despues → al-02:Disponible  el-01:Disponible  pl-01:Disponible  ca-01:Disponible  ma-01:Disponible

Resultado E2 → CORRECTO: 5/5 contratos expirados
```

---

## Escenario 3 — Calificación bloqueada hasta finalizar

**Mecanismo demostrado: `Semaphore` por contrato**

5 hilos intentan calificar simultáneamente, cada uno con un contrato activo distinto.
Todos quedan bloqueados en `semaforo.acquire()` hasta que el hilo principal
envía los 5 `FINALIZAR`, liberando los semáforos uno a uno.

```
Todos los hilos bloqueados. Enviando FINALIZAR a los 5 contratos...
[al-03] Calificacion desbloqueada → OK 5,0
[el-02] Calificacion desbloqueada → OK 4,5
[pl-02] Calificacion desbloqueada → OK 5,0
[ca-02] Calificacion desbloqueada → OK 4,5
[ma-02] Calificacion desbloqueada → OK 4,5

Resultado E3 → CORRECTO: 5/5 hilos desbloqueados
```

---

## Escenario 4 — Benchmark de throughput

**Mecanismo demostrado: todos en conjunto bajo carga sostenida**

30 hilos durante 10 segundos: 20 de lectura (`BUSCAR`, `LISTAR`, `OBTENER`, `STATS`)
y 10 de escritura (`CONTRATAR` + `FINALIZAR` en ciclo).

```
  Operación    │ Requests │     OK │ Errores │    Req/s │ Lat. prom
  ─────────────────────────────────────────────────────────────────
  BUSCAR       │    21259 │  21259 │       0 │   2125,5 │    2,3 ms
  LISTAR       │    21253 │  21253 │       0 │   2124,9 │    2,4 ms
  OBTENER      │    21262 │  21262 │       0 │   2125,8 │    2,3 ms
  STATS        │    21121 │  21121 │       0 │   2111,7 │    2,3 ms
  CONTRATAR    │    28312 │  14166 │   14146 │   2830,6 │    2,4 ms
  FINALIZAR    │    14166 │  14166 │       0 │   1416,3 │    2,3 ms
  ─────────────────────────────────────────────────────────────────
  TOTAL        │   127373 │ 113227 │   14146 │  12734,8 │
```

**12.735 req/s — 127.373 requests totales — 88,9% de éxito**

### Interpretación

- **Los ~14k errores en CONTRATAR son esperados**, no son bugs. Son 10 hilos compitiendo por
  5 workers: cuando dos hilos llegan al mismo worker al mismo tiempo, uno gana y el otro
  recibe `ERROR trabajador ocupado`. Es exactamente el Escenario 1 replicado miles de veces
  bajo carga real. El 88,9% de éxito es estable entre corridas porque la proporción
  hilos/workers no cambia.

- **Latencia de 2,3 ms** es el RTT de loopback TCP (`connect → write → read → close`).
  El servidor procesa cada comando en microsegundos; el costo dominante es la conexión TCP.

- **Lecturas y escrituras tienen la misma latencia**, lo que confirma que el `ReadWriteLock`
  no introduce overhead observable: las lecturas no se bloquean entre sí y corren en paralelo
  a la misma velocidad que si no hubiera sincronización.

---

## Hilos lanzados en total

| Fase | Hilos (stress client) |
|---|---|
| Carga inicial | 0 — secuencial desde `main` |
| Escenario 1 | 50 — `newFixedThreadPool(50)` |
| Escenario 2 | 0 — secuencial desde `main` |
| Escenario 3 | 5 — un `Thread` calificador por worker |
| Escenario 4 | 30 — pool (20 lecturas + 10 escrituras) |
| **Total cliente** | **85 hilos** |

Del lado del servidor, `ServidorTCP` lanza un hilo `ManejadorCliente` por cada conexión TCP.
Como cada `enviar()` abre una conexión nueva, el servidor creó aproximadamente
**127.000 hilos** solo durante el benchmark (son daemon y mueren en milisegundos,
pero se instancian uno por uno).