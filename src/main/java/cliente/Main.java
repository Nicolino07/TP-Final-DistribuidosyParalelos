package cliente;

import java.io.*;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Main {

    private static final String HOST   = "localhost";
    private static final int    PUERTO = 9090;

    // -------------------------------------------------------
    // Datos realistas para la carga inicial
    // -------------------------------------------------------
    private static final Object[][] TRABAJADORES = {
        {"al-01","Roberto","ALBANIL",    "Albanil con 15 años de experiencia en obra gruesa"},
        {"al-02","Miguel", "ALBANIL",    "Especialista en revoques y cimientos"},
        {"al-03","Diego",  "ALBANIL",    "Albanil matriculado, zona norte"},
        {"al-04","Facundo","ALBANIL",    "Albanil y pintor, trabajos interiores"},
        {"al-05","Gonzalo","ALBANIL",    "Albanil con experiencia en piscinas"},
        {"el-01","Maria",  "ELECTRICISTA","Electricista matriculada, instalaciones industriales"},
        {"el-02","Laura",  "ELECTRICISTA","Electricista, domotica y paneles solares"},
        {"el-03","Sofia",  "ELECTRICISTA","Electricista residencial y comercial"},
        {"el-04","Ana",    "ELECTRICISTA","Electricista, tableros y media tension"},
        {"pl-01","Carlos", "PLOMERO",    "Plomero gasista, instalaciones nuevas y reparaciones"},
        {"pl-02","Pedro",  "PLOMERO",    "Plomero matriculado, urgencias 24hs"},
        {"pl-03","Hector", "PLOMERO",    "Plomero especializado en calefaccion"},
        {"pl-04","Mario",  "PLOMERO",    "Plomero, desagues y cloacas"},
        {"ca-01","Jorge",  "CARPINTERO", "Carpintero muebles a medida y restauracion"},
        {"ca-02","Luis",   "CARPINTERO", "Carpintero, decks y estructuras de madera"},
        {"ca-03","Ricardo","CARPINTERO", "Carpintero especializado en aberturas"},
        {"ma-01","Alberto","MAESTRO",    "Maestro mayor de obras, direccion tecnica"},
        {"ma-02","Fernando","MAESTRO",   "Maestro de obra, refacciones y ampliaciones"},
        {"pr-01","Patricia","PROFESOR",  "Profesora de matematica y fisica, nivel secundario"},
        {"pr-02","Claudia", "PROFESOR",  "Profesora particular, primaria y secundaria"},
    };

    // Calificaciones históricas para poblar el grafo con datos reales
    private static final Object[][] HISTORIAL = {
        {"al-01", 5, "Excelente trabajo, muy prolijo"},
        {"al-01", 4, "Buen trabajo, puntual"},
        {"al-01", 5, "Lo recomiendo sin dudas"},
        {"al-02", 3, "Trabajo aceptable, demoró más de lo pactado"},
        {"al-02", 4, "Bien en general"},
        {"al-03", 5, "Perfecto, muy limpio"},
        {"al-04", 2, "Tardó mucho, resultado regular"},
        {"al-05", 4, "Buen trabajo con la piscina"},
        {"el-01", 5, "Instalacion impecable, muy profesional"},
        {"el-01", 5, "Rapida y eficiente"},
        {"el-02", 4, "Buen trabajo con la domotica"},
        {"el-03", 3, "Cumplió pero sin destacarse"},
        {"el-04", 5, "Excelente, resolvio el problema en el dia"},
        {"pl-01", 4, "Solucionó la perdida rapidamente"},
        {"pl-01", 5, "Muy recomendable"},
        {"pl-02", 5, "Llego en menos de una hora, problema resuelto"},
        {"pl-03", 4, "Buen trabajo con la caldera"},
        {"pl-04", 3, "Tardo pero quedó bien"},
        {"ca-01", 5, "Los muebles quedaron hermosos"},
        {"ca-02", 4, "Buen deck, prolijo"},
        {"ca-03", 5, "Las ventanas quedaron perfectas"},
        {"ma-01", 5, "Excelente direccion de obra"},
        {"ma-02", 4, "Buena coordinacion del trabajo"},
        {"pr-01", 5, "Mi hijo aprobó gracias a ella"},
        {"pr-02", 4, "Muy didactica y paciente"},
    };

    // Workers dedicados al benchmark (no se usan en otros escenarios)
    private static final String[] IDS_BENCH = {
        "bench-01","bench-02","bench-03","bench-04","bench-05"
    };

    record OpResult(String tipo, boolean ok, long latencyMs) {}

    public static void main(String[] args) throws InterruptedException {
        imprimir("╔══════════════════════════════════════════════╗");
        imprimir("║  STRESS CLIENT / Distribuidos y Paralelos    ║");
        imprimir("║  Servidor: " + HOST + ":" + PUERTO +"                    ║");
        imprimir("╚══════════════════════════════════════════════╝");

        faseCargaInicial();

        imprimir("\n>>> Escenario 1: Doble contratación simultanea / 50 hilos (ReadWriteLock)");
        escenario1("al-01", 50);
        Thread.sleep(500);

        imprimir("\n>>> Escenario 2: 5 contratos expirando en paralelo (Monitor wait/notifyAll)");
        escenario2(new String[]{"al-02","el-01","pl-01","ca-01","ma-01"});
        Thread.sleep(500);

        imprimir("\n>>> Escenario 3: 5 calificaciones bloqueadas simultaneas (Semaforo)");
        escenario3(new String[]{"al-03","el-02","pl-02","ca-02","ma-02"});
        Thread.sleep(500);

        imprimir("\n>>> Escenario 4: Benchmark de throughput -> 30 hilos x 10 segundos");
        escenario4();

        imprimir("\n=== FIN DEL TEST DE ESTRÉS ===");

        // Al ejecutar esto, la JVM mata cualquier hilo remanente y cierra la terminal en el acto
        System.exit(0);

    }

    // -------------------------------------------------------
    // FASE 0: Carga inicial — registra 20 trabajadores y carga
    // historial de calificaciones para poblar el grafo.
    // -------------------------------------------------------
    private static void faseCargaInicial() throws InterruptedException {
        imprimir("\n>>> [CARGA INICIAL] Registrando trabajadores y cargando historial...");

        int registrados = 0;
        for (Object[] t : TRABAJADORES) {
            String resp = enviar(String.format("REGISTRAR %s %s %s \"%s\"", t[0], t[1], t[2], t[3]));
            if ("OK".equals(resp)) registrados++;
            Thread.sleep(10); // LE DA 10MS A WINDOWS PARA RESPIRAR Y NO AGOTAR LOS PUERTOS
        }

        // Workers para el benchmark
        for (String id : IDS_BENCH) {
            enviar(String.format("REGISTRAR %s Bench-Worker ALBANIL \"Worker de benchmark\"", id));
            Thread.sleep(10);
        }

        // Cargar historial: ciclo contratar → finalizar → calificar secuencial
        int calificaciones = 0;
        for (int i = 0; i < HISTORIAL.length; i++) {
            Object[] h = HISTORIAL[i];
            String idT = (String) h[0];
            int    pts = (int)   h[1];
            String com = (String) h[2];
            String idC = "hist-c" + i;
            String fin = LocalDateTime.now().plusSeconds(1).toString();
            String resp = enviar(String.format("CONTRATAR %s %s \"Trabajo previo\" \"%s\"", idT, idC, fin));
            if (resp != null && resp.startsWith("OK contrato:")) {
                String idContrato = resp.substring("OK contrato:".length()).trim();
                enviar("FINALIZAR " + idT + " " + idContrato);
                String cal = enviar(String.format("CALIFICAR %s %s %d \"%s\"", idT, idContrato, pts, com));
                if (cal != null && cal.startsWith("OK")) calificaciones++;
            }
        }

        imprimir(String.format("  Trabajadores registrados : %d (+ %d de benchmark)", registrados, IDS_BENCH.length));
        imprimir(String.format("  Calificaciones históricas: %d", calificaciones));
        imprimir("  Top albaniles: " + enviar("BUSCAR ALBANIL"));
        imprimir("  Top electricistas: " + enviar("BUSCAR ELECTRICISTA"));
        imprimir("  " + enviar("STATS"));
    }

    // -------------------------------------------------------
    // ESCENARIO 1: N hilos compiten por el mismo trabajador.
    // Solo uno gana — el resto recibe ERROR de trabajador ocupado.
    // -------------------------------------------------------
    private static void escenario1(String idTrabajador, int n) throws InterruptedException {
        ExecutorService pool    = Executors.newFixedThreadPool(n);
        CountDownLatch  listos  = new CountDownLatch(n);
        CountDownLatch  largada = new CountDownLatch(1);
        AtomicInteger   ganaron    = new AtomicInteger(0);
        AtomicInteger   rechazados = new AtomicInteger(0);
        List<String>    contratos  = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < n; i++) {
            final String idC = "e1-c" + i;
            pool.submit(() -> {
                listos.countDown();
                try {
                    largada.await();
                    String fin  = LocalDateTime.now().plusMinutes(30).toString();
                    String resp = enviar(String.format(
                        "CONTRATAR %s %s \"Trabajo stress\" \"%s\"", idTrabajador, idC, fin));
                    if (resp != null && resp.startsWith("OK")) {
                        ganaron.incrementAndGet();
                        contratos.add(resp.substring("OK contrato:".length()).trim());
                        imprimir("  [" + idC + "] CONTRATADO");
                    } else {
                        rechazados.incrementAndGet();
                        imprimir("  [" + idC + "] RECHAZADO  = " + resp);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        listos.await();
        largada.countDown();
        pool.shutdown();
        pool.awaitTermination(15, TimeUnit.SECONDS);

        imprimir(String.format("  Resultado E1 = Ganaron: %d | Rechazados: %d  (esperado: 1 ganador, %d rechazados)",
            ganaron.get(), rechazados.get(), n - 1));

        // Limpiar: finalizar el contrato ganador para dejar el worker libre
        if (!contratos.isEmpty()) {
            enviar("FINALIZAR " + idTrabajador + " " + contratos.get(0));
        }
    }

    // -------------------------------------------------------
    // ESCENARIO 2: Varios contratos expiran simultáneamente.
    // El HiloExpiracionContratos debe liberarlos a todos.
    // -------------------------------------------------------
    private static void escenario2(String[] trabajadores) throws InterruptedException {
        List<String> idContratos = new ArrayList<>();

        for (String id : trabajadores) {
            String fin  = LocalDateTime.now().plusSeconds(4).toString();
            String resp = enviar(String.format(
                "CONTRATAR %s e2-c \"%s\" \"%s\"", id, "Trabajo corto", fin));
            if (resp != null && resp.startsWith("OK contrato:")) {
                idContratos.add(resp.substring("OK contrato:".length()).trim());
                imprimir("  Contrato creado para " + id + " = expira en 4s");
            } else {
                imprimir("  SKIP " + id + ": " + resp);
            }
        }

        imprimir("  Estado antes  = " + estadoResumido(trabajadores));
        imprimir("  Esperando 8s para que HiloExpiracion actue...");
        Thread.sleep(8000);
        imprimir("  Estado despues = " + estadoResumido(trabajadores));

        long liberados = Arrays.stream(trabajadores)
            .map(id -> enviar("OBTENER " + id))
            .filter(r -> r != null && r.contains("Disponible"))
            .count();

        boolean ok = liberados == trabajadores.length;
        imprimir(String.format("  Resultado E2 = %s: %d/%d contratos expirados",
            ok ? "CORRECTO" : "PARCIAL", liberados, trabajadores.length));
    }

    // -------------------------------------------------------
    // ESCENARIO 3: N calificaciones bloqueadas simultáneamente.
    // Cada hilo calificador espera su propio semáforo de contrato.
    // -------------------------------------------------------
    private static void escenario3(String[] trabajadores) throws InterruptedException {
        // Crear contratos para todos los workers
        Map<String, String> contratos = new LinkedHashMap<>();
        for (String id : trabajadores) {
            String fin  = LocalDateTime.now().plusMinutes(30).toString();
            String resp = enviar(String.format(
                "CONTRATAR %s e3-c \"%s\" \"%s\"", id, "Trabajo a calificar", fin));
            if (resp != null && resp.startsWith("OK contrato:")) {
                contratos.put(id, resp.substring("OK contrato:".length()).trim());
                imprimir("  Contrato activo: " + id);
            }
        }

        CountDownLatch todosListos = new CountDownLatch(contratos.size());
        AtomicInteger  desbloqueados = new AtomicInteger(0);

        // Lanzar un hilo calificador por cada contrato — todos se bloquearán
        List<Thread> hilosCalif = new ArrayList<>();
        for (Map.Entry<String, String> e : contratos.entrySet()) {
            String idT = e.getKey(), idCont = e.getValue();
            Thread t = new Thread(() -> {
                todosListos.countDown();
                String r = enviar(String.format("CALIFICAR %s %s 5 \"Excelente\"", idT, idCont));
                if (r != null && r.startsWith("OK")) {
                    desbloqueados.incrementAndGet();
                    imprimir("  [" + idT + "] Calificacion desbloqueada = " + r);
                }
            }, "Calificador-" + idT);
            t.setDaemon(true);
            t.start();
            hilosCalif.add(t);
        }

        todosListos.await();
        Thread.sleep(1500); // tiempo para que todos estén bloqueados en acquire()

        imprimir("  Todos los hilos bloqueados. Enviando FINALIZAR a los " + contratos.size() + " contratos...");
        for (Map.Entry<String, String> e : contratos.entrySet()) {
            enviar("FINALIZAR " + e.getKey() + " " + e.getValue());
        }

        for (Thread t : hilosCalif) t.join(5000);

        boolean ok = desbloqueados.get() == contratos.size();
        imprimir(String.format("  Resultado E3 = %s: %d/%d hilos desbloqueados",
            ok ? "CORRECTO" : "PARCIAL", desbloqueados.get(), contratos.size()));
    }

    // -------------------------------------------------------
    // ESCENARIO 4: Benchmark de throughput.
    // 20 hilos de lectura + 10 hilos de escritura durante 10 segundos.
    // Mide requests/segundo y latencia promedio por tipo de operación.
    // -------------------------------------------------------
    private static void escenario4() throws InterruptedException {
        final int HILOS_LECTURA  = 20;
        final int HILOS_ESCRITURA = 10;
        final int DURACION_MS    = 10_000;

        ConcurrentLinkedQueue<OpResult> resultados = new ConcurrentLinkedQueue<>();
        CountDownLatch inicio = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(HILOS_LECTURA + HILOS_ESCRITURA);

        // --- HILOS DE LECTURA CON CONEXIÓN PERSISTENTE ---
        String[] oficios = {"ALBANIL","ELECTRICISTA","PLOMERO","CARPINTERO"};
        for (int i = 0; i < HILOS_LECTURA; i++) {
            pool.submit(() -> {
                try { inicio.await(); } catch (InterruptedException e) { return; }
                long fin = System.currentTimeMillis() + DURACION_MS;
                Random rnd = new Random();

                // OPTIMIZACIÓN: Cada hilo abre UN SOLO socket para usarlo todo el tiempo
                try (
                        Socket s = new Socket(HOST, PUERTO);
                        PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true);
                        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))
                ) {
                    while (System.currentTimeMillis() < fin) {
                        String[] ops = {
                                "BUSCAR " + oficios[rnd.nextInt(oficios.length)],
                                "LISTAR",
                                "OBTENER al-0" + (rnd.nextInt(5) + 1),
                                "STATS"
                        };
                        String cmd  = ops[rnd.nextInt(ops.length)];
                        String tipo = cmd.split(" ")[0];

                        long t0 = System.currentTimeMillis();
                        out.println(cmd);          // Envía por el socket persistente
                        String resp = in.readLine(); // Lee por el socket persistente
                        long lat = System.currentTimeMillis() - t0;

                        resultados.add(new OpResult(tipo, resp != null && !resp.startsWith("ERROR"), lat));
                    }
                } catch (IOException e) {
                    // Si se cae la red de este hilo, termina pacíficamente
                }
            });
        }

        // --- HILOS DE ESCRITURA CON CONEXIÓN PERSISTENTE ---
        for (int i = 0; i < HILOS_ESCRITURA; i++) {
            final int idx = i;
            pool.submit(() -> {
                try { inicio.await(); } catch (InterruptedException e) { return; }
                long fin = System.currentTimeMillis() + DURACION_MS;
                String idT = IDS_BENCH[idx % IDS_BENCH.length];
                String idC = "bench-usr-" + idx;

                // Un solo socket para toda la ráfaga de escrituras
                try (
                        Socket s = new Socket(HOST, PUERTO);
                        PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true);
                        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))
                ) {
                    while (System.currentTimeMillis() < fin) {
                        String finEstimado = LocalDateTime.now().plusMinutes(1).toString();

                        // 1. CONTRATAR
                        long t0 = System.currentTimeMillis();
                        out.println(String.format("CONTRATAR %s %s \"Bench\" \"%s\"", idT, idC, finEstimado));
                        String r1 = in.readLine();
                        long latC = System.currentTimeMillis() - t0;

                        boolean contratado = r1 != null && r1.startsWith("OK contrato:");
                        resultados.add(new OpResult("CONTRATAR", contratado, latC));

                        // 2. FINALIZAR (Solo si se pudo contratar)
                        if (contratado) {
                            String idCont = r1.substring("OK contrato:".length()).trim();
                            t0 = System.currentTimeMillis();
                            out.println("FINALIZAR " + idT + " " + idCont);
                            String r2 = in.readLine();
                            resultados.add(new OpResult("FINALIZAR", "OK".equals(r2), System.currentTimeMillis() - t0));
                        }
                    }
                } catch (IOException e) {
                    // Manejo de caída de hilo
                }
            });
        }

        imprimir(String.format("  Lecturas: %d hilos  |  Escrituras: %d hilos  |  Duración: %ds",
                HILOS_LECTURA, HILOS_ESCRITURA, DURACION_MS / 1000));
        imprimir("  Corriendo...");

        long tInicio = System.currentTimeMillis();
        inicio.countDown();
        pool.shutdown();
        if (!pool.awaitTermination(DURACION_MS + 5000, TimeUnit.MILLISECONDS)) {
            pool.shutdownNow();}
        long duracionReal = System.currentTimeMillis() - tInicio;

        Map<String, List<OpResult>> porTipo = resultados.stream()
                .collect(Collectors.groupingBy(OpResult::tipo));

        imprimir("");
        imprimir(String.format("  %-12s │ %8s │ %6s │ %7s │ %8s │ %9s",
                "Operación", "Requests", "OK", "Errores", "Req/s", "Lat. prom"));
        imprimir("  " + "─".repeat(68));

        long totalReqs = 0, totalOK = 0;
        for (String tipo : new String[]{"BUSCAR","LISTAR","OBTENER","STATS","CONTRATAR","FINALIZAR"}) {
            List<OpResult> ops = porTipo.getOrDefault(tipo, List.of());
            if (ops.isEmpty()) continue;
            long ok     = ops.stream().filter(OpResult::ok).count();
            long err    = ops.size() - ok;
            double rps  = ops.size() * 1000.0 / duracionReal;
            double lat  = ops.stream().mapToLong(OpResult::latencyMs).average().orElse(0);
            imprimir(String.format("  %-12s │ %8d │ %6d │ %7d │ %8.1f │ %7.1f ms",
                    tipo, ops.size(), ok, err, rps, lat));
            totalReqs += ops.size();
            totalOK   += ok;
        }
        imprimir("  " + "─".repeat(68));
        imprimir(String.format("  %-12s │ %8d │ %6d │ %7d │ %8.2f │",
                "TOTAL", totalReqs, totalOK, totalReqs - totalOK,
                totalReqs * 1000.0 / duracionReal));

        imprimir(String.format("%n  Resultado E4 = %.0f req/s  |  %d requests totales  |  %.1f%% éxito",
                totalReqs * 1000.0 / duracionReal, totalReqs,
                totalOK * 100.0 / totalReqs));
    }
    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private static String estadoResumido(String[] ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            String r = enviar("OBTENER " + id);
            String estado = r != null ? (r.contains("Disponible") ? "Disponible" : "En trabajo") : "?";
            sb.append(id).append(":").append(estado).append("  ");
        }
        return sb.toString().trim();
    }

    private static String enviar(String comando) {
        try (
            Socket         s   = new Socket(HOST, PUERTO);
            PrintWriter    out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true);
            BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream()))
        ) {
            out.println(comando);
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static void imprimir(String msg) {
        System.out.println(msg);
    }
}