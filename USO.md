## Para probar el programa

**1.** En un terminal en la raíz del proyecto, encender el servidor:

```
java -jar target/servidor.jar
```

**2.** En otra terminal en la raíz del proyecto, encender el cliente de stress:

```
java -jar target/stress-client.jar
```

Corre los 4 escenarios del TP y termina solo:
- **Escenario 1:** 50 hilos compitiendo por el mismo worker → demuestra `ReadWriteLock`
- **Escenario 2:** contratos expirando en paralelo → demuestra `Monitor` / `HiloExpiracionContratos`
- **Escenario 3:** calificaciones bloqueadas simultáneas → demuestra `Semáforo`
- **Escenario 4:** benchmark de throughput — 20 lectores + 10 escritores durante 10 segundos

**3.** Abrir el dashboard en el navegador:

```
http://localhost:9091
```

El botón **"Iniciar simulación"** del dashboard es independiente del cliente de stress: lanza un stress continuo e indefinido (10 lectores + 8 escritores) que visualiza en tiempo real los ciclos `CONTRATAR → FINALIZAR → CALIFICAR` sobre las tarjetas de cada worker. Se puede correr junto con el cliente de stress o por separado.

---

## Panel TCP directo del dashboard

El dashboard tiene un panel para enviar comandos al servidor en tiempo real.

**Botones de lectura:** PING · STATS · LISTAR · GUARDAR · BUSCAR por oficio · OBTENER (selector con todos los workers)

**Formularios de escritura:**
- **REGISTRAR** — id, nombre, oficio, descripción
- **CONTRATAR** — selector de worker (todos, incluidos ocupados para forzar errores) + nombre consumidor + duración en minutos
- **FINALIZAR** — selector de workers en trabajo, id de contrato pre-cargado automáticamente
- **CALIFICAR** — aparece disponible después de FINALIZAR, con selector de estrellas y comentario opcional

**Para observar el Semáforo manualmente:**
1. CONTRATAR un worker → tarjeta muestra `▓ BLOQUEADO`
2. FINALIZAR desde el panel → tarjeta cambia a `libre` al instante
3. CALIFICAR → estrellas se actualizan

---

## Comandos TCP disponibles

El servidor escucha en el puerto `9090`. Se puede interactuar desde el panel del dashboard o con cualquier cliente TCP como `telnet` o `nc`.

| Comando | Formato |
|---------|---------|
| `REGISTRAR` | `REGISTRAR <id> <nombre> <oficio> "<descripcion>"` |
| `CONTRATAR` | `CONTRATAR <idTrabajador> <idConsumidor> "<desc>" "<finEstimado>"` |
| `FINALIZAR` | `FINALIZAR <idTrabajador> <idContrato>` |
| `CALIFICAR` | `CALIFICAR <idTrabajador> <idContrato> <1-5> "<comentario>"` |
| `BUSCAR` | `BUSCAR <oficio>` |
| `LISTAR` | `LISTAR` |
| `OBTENER` | `OBTENER <idTrabajador>` |
| `STATS` | `STATS` |
| `PING` | `PING` |
| `GUARDAR` | `GUARDAR` |

**Oficios válidos:** `ALBANIL` · `ELECTRICISTA` · `PLOMERO` · `CARPINTERO` · `MAESTRO` · `PROFESOR`

**Fecha para CONTRATAR:** formato ISO-8601 — ejemplo: `2025-06-13T18:30:00`

**Flujo de un contrato:** `CONTRATAR` → `FINALIZAR` → `CALIFICAR` (en ese orden obligatorio)
