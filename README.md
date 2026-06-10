# TP Final — Distribuidos y Paralelos
## Aplicación de Conceptos de Concurrencia y Regiones Críticas

Plataforma de contratación de servicios (tipo Uber de oficios) desarrollada en Java 17, diseñada para demostrar los conceptos teóricos de concurrencia, regiones críticas y mecanismos de sincronización.

---

## Idea central

El objetivo no es construir una aplicación completa, sino demostrar de forma concreta qué ocurre cuando múltiples usuarios acceden al mismo dato al mismo tiempo sin control, y cómo los mecanismos de sincronización resuelven el problema.

Para eso construimos dos programas que corren en paralelo:
- **Aplicación principal** — servidor TCP + interfaz gráfica + grafo en memoria
- **Stress client** — cliente de carga que martilla al servidor con decenas de hilos simultáneos para provocar y evidenciar los problemas de concurrencia

---

## Los tres escenarios de concurrencia

### Escenario 1 — Doble contratación simultánea
**Mecanismo: `ReadWriteLock` por nodo**

Dos consumidores intentan contratar al mismo trabajador al mismo tiempo. Solo uno puede ganar — el segundo recibe error de trabajador ocupado. Sin sincronización ambos creen haber contratado al mismo trabajador.

### Escenario 2 — Contrato que expira
**Mecanismo: Monitor con `wait`/`notifyAll`**

Un hilo del sistema (`HiloExpiracionContratos`) revisa periódicamente los contratos activos y libera al trabajador cuando el tiempo estimado vence. Este hilo compite con los hilos de usuario por el lock del nodo.

### Escenario 3 — Calificación solo al finalizar
**Mecanismo: Semáforo por contrato**

Un consumidor solo puede calificar a un trabajador si tiene un contrato en estado `FINALIZADO` con él. El semáforo bloquea el intento de calificación hasta que el contrato cambia de estado.

---

## Arquitectura

```
TP-Final-DistribuidosyParalelos/
├── servidor/          — Proceso 1: aplicación principal
│   └── src/main/java/com/tp/distribuidos/servidor/
│       ├── modelo/
│       │   ├── Oficio.java
│       │   ├── EstadoTrabajador.java
│       │   ├── EstadoContrato.java
│       │   ├── Trabajador.java
│       │   ├── Consumidor.java
│       │   ├── Contrato.java
│       │   ├── Calificacion.java
│       │   ├── NodoTrabajador.java        ← región crítica
│       │   └── GrafoTrabajadores.java     ← contenedor del grafo
│       ├── HiloExpiracionContratos.java   ← escenario 2
│       ├── PersistenciaGrafo.java         ← serialización a disco
│       ├── ProtocoloParser.java           ← parser de comandos TCP
│       ├── ManejadorCliente.java          ← atiende una conexión TCP
│       ├── ServidorTCP.java               ← acepta conexiones
│       └── Main.java                      ← arranca todo
│
└── stress-client/     — Proceso 2: cliente de carga
    └── src/main/java/com/tp/distribuidos/cliente/
        └── Main.java
```

---

## Modelo de datos

Los trabajadores se almacenan en un **grafo en memoria**:
- Cada trabajador es un **nodo** con su perfil y estado
- Cada calificación recibida es una **arista con peso** (puntaje 1-5)
- El grafo vive en un `ConcurrentHashMap` — thread-safe para operaciones sobre la estructura
- Cada nodo tiene su propio `ReentrantReadWriteLock` — máximo paralelismo entre nodos independientes

```
Consumidor A ──[5★]──► Plomero Juan   ← nodo con su propio lock
Consumidor B ──[3★]──► Plomero Juan   ← acceso concurrente al mismo nodo
Consumidor C ──[4★]──► Electricista Ana ← nodo independiente, lock independiente
```

---

## Sincronización — decisiones de diseño

| Mecanismo | Dónde se usa | Por qué |
|---|---|---|
| `ReentrantReadWriteLock` por nodo | Contratar, finalizar, calificar, leer perfil | Distingue lecturas de escrituras. Múltiples lecturas en paralelo, escrituras exclusivas. |
| `ConcurrentHashMap` | Estructura del grafo | Thread-safe nativo. `putIfAbsent` atómico para registros simultáneos. |
| Monitor `wait`/`notifyAll` | `HiloExpiracionContratos` | El hilo duerme eficientemente y puede ser despertado al crear un contrato nuevo. |
| `Semaphore` por contrato | Habilitar calificación | Bloquea al consumidor hasta que el contrato cambia a `FINALIZADO`. |

---

## Protocolo TCP

Texto plano, una línea por comando:

```
REGISTRAR <id> <nombre> <oficio> "<descripcion>"
CONTRATAR <idTrabajador> <idConsumidor> "<descripcion>" "<finEstimado>"
FINALIZAR <idTrabajador> <idContrato>
CALIFICAR <idTrabajador> <idContrato> <puntaje> "<comentario>"
BUSCAR <oficio>
LISTAR
OBTENER <idTrabajador>
STATS
PING
GUARDAR
```

Ejemplo de sesión:
```
→ REGISTRAR j01 Juan ALBANIL "10 años de experiencia"
← OK

→ CONTRATAR j01 c01 "Reparar pared" "2024-12-31T18:00"
← OK contrato:abc-123

→ FINALIZAR j01 abc-123
← OK

→ CALIFICAR j01 abc-123 5 "Excelente trabajo"
← OK 5.0

→ BUSCAR ALBANIL
← j01:5.0
```

---

## Stack tecnológico

- **Java 17 LTS**
- **Maven** multi-módulo (`servidor` + `stress-client`)
- **Swing** para la interfaz gráfica
- **Serialización Java nativa** para persistencia (`grafo.dat`)
- Sin dependencias externas — todo con la biblioteca estándar de Java

---

## Requisitos

- Java 17+
- Maven 3.8+

---

## Compilar

```bash
mvn package
```

---

## Ejecutar

```bash
# Proceso 1 — aplicación principal
java -jar servidor/target/servidor-1.0-SNAPSHOT.jar

# Proceso 2 — stress client (en otra terminal)
java -jar stress-client/target/stress-client-1.0-SNAPSHOT.jar
```

---

## Persistencia

Al cerrar la aplicación el grafo se serializa automáticamente a `grafo.dat`. Al reiniciar se carga desde ese archivo. Si el archivo no existe arranca con un grafo vacío.

---

## Estado actual del desarrollo

- [x] Modelo de datos (`modelo/`)
- [x] `NodoTrabajador` con los tres mecanismos de sincronización
- [x] `GrafoTrabajadores`
- [x] `HiloExpiracionContratos`
- [x] `PersistenciaGrafo`
- [x] `ProtocoloParser`
- [ ] `ManejadorCliente`
- [ ] `ServidorTCP`
- [ ] `Main` servidor
- [ ] Interfaz gráfica (Swing)
- [ ] Stress client
- [ ] Paper / informe