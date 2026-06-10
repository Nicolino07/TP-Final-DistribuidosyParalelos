package cliente;

import java.io.*;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stress client — demuestra los tres escenarios de concurrencia.
 *
 * Escenario 1: N hilos intentan contratar al mismo trabajador simultáneamente.
 *              Solo uno gana; el resto recibe ERROR de trabajador ocupado.
 *
 * Escenario 2: Crea un contrato que expira en 3 segundos y verifica que
 *              el HiloExpiracionContratos lo libera automáticamente.
 *
 * Escenario 3: Un hilo intenta calificar antes de que el contrato finalice.
 *              La llamada bloquea hasta que otro hilo envía FINALIZAR.
 */
public class Main {

    private static final String HOST   = "localhost";
    private static final int    PUERTO = 9090;

    private static final AtomicInteger totalOK     = new AtomicInteger(0);
    private static final AtomicInteger totalError  = new AtomicInteger(0);

    public static void main(String[] args) throws InterruptedException {
        System.out.println("========================================");
        System.out.println("  STRESS CLIENT — Distribuidos y Paralelos");
        System.out.println("  Servidor: " + HOST + ":" + PUERTO);
        System.out.println("========================================\n");

        // Registrar trabajadores de prueba (ignora duplicados entre corridas)
        enviar("REGISTRAR stress-t1 Juan ALBANIL \"Albanil de prueba\"");
        enviar("REGISTRAR stress-t2 Ana  ELECTRICISTA \"Electricista de prueba\"");
        enviar("REGISTRAR stress-t3 Carlos PLOMERO \"Plomero de prueba\"");

        System.out.println(">>> Escenario 1: Doble contratacion simultanea (ReadWriteLock)");
        escenario1("stress-t1");
        Thread.sleep(500);

        System.out.println("\n>>> Escenario 2: Contrato que expira (Monitor wait/notifyAll)");
        escenario2("stress-t3");
        Thread.sleep(500);

        System.out.println("\n>>> Escenario 3: Calificacion bloqueada hasta finalizar (Semaforo)");
        escenario3("stress-t2");

        System.out.println("\n========================================");
        System.out.println("  RESUMEN");
        System.out.printf ("  OK: %d  |  Errores esperados: %d%n", totalOK.get(), totalError.get());
        System.out.println("========================================");
    }

    // -------------------------------------------------------
    // Escenario 1: N hilos compiten por el mismo trabajador
    // -------------------------------------------------------
    private static void escenario1(String idTrabajador) throws InterruptedException {
        final int N = 10;
        ExecutorService pool    = Executors.newFixedThreadPool(N);
        CountDownLatch  listos  = new CountDownLatch(N);
        CountDownLatch  largada = new CountDownLatch(1);
        AtomicInteger   ganaron = new AtomicInteger(0);
        AtomicInteger   perdieron = new AtomicInteger(0);

        for (int i = 0; i < N; i++) {
            final String idConsumidor = "c-e1-" + i;
            pool.submit(() -> {
                listos.countDown();
                try {
                    largada.await(); // todos esperan juntos
                    String fin  = LocalDateTime.now().plusMinutes(30).toString();
                    String resp = enviar("CONTRATAR " + idTrabajador + " " + idConsumidor
                            + " \"Trabajo stress\" \"" + fin + "\"");
                    if (resp != null && resp.startsWith("OK")) {
                        ganaron.incrementAndGet();
                        totalOK.incrementAndGet();
                        System.out.println("  [" + idConsumidor + "] CONTRATADO: " + resp);
                    } else {
                        perdieron.incrementAndGet();
                        totalError.incrementAndGet();
                        System.out.println("  [" + idConsumidor + "] RECHAZADO:  " + resp);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        listos.await();
        largada.countDown(); // ¡ya! todos salen al mismo tiempo
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        System.out.println("  Resultado E1 → Ganaron: " + ganaron.get()
                + " | Rechazados: " + perdieron.get()
                + " (esperado: 1 ganador, " + (N - 1) + " rechazados)");

        // Limpiar: finalizar el contrato activo para el siguiente escenario
        String nodo = enviar("OBTENER " + idTrabajador);
        // No tenemos el idContrato aquí, pero lo dejamos vencer o usamos FINALIZAR con dummy
    }

    // -------------------------------------------------------
    // Escenario 2: contrato con expiración corta
    // -------------------------------------------------------
    private static void escenario2(String idTrabajador) throws InterruptedException {
        // Asegurar que el trabajador esté disponible
        enviar("REGISTRAR " + idTrabajador + " Carlos PLOMERO \"Plomero de prueba\"");

        String fin  = LocalDateTime.now().plusSeconds(4).toString();
        String resp = enviar("CONTRATAR " + idTrabajador + " c-e2 \"Trabajo corto\" \"" + fin + "\"");
        System.out.println("  Contrato creado: " + resp);

        System.out.println("  Estado antes : " + enviar("OBTENER " + idTrabajador));
        System.out.println("  Esperando 8s para que HiloExpiracion actue...");
        Thread.sleep(8000);
        String estadoDespues = enviar("OBTENER " + idTrabajador);
        System.out.println("  Estado despues: " + estadoDespues);

        boolean ok = estadoDespues != null && estadoDespues.contains("Disponible");
        System.out.println("  Resultado E2 → " + (ok ? "CORRECTO: trabajador volvio a Disponible" : "ERROR: no cambio estado"));
        (ok ? totalOK : totalError).incrementAndGet();
    }

    // -------------------------------------------------------
    // Escenario 3: calificacion bloqueada por semaforo
    // -------------------------------------------------------
    private static void escenario3(String idTrabajador) throws InterruptedException {
        String fin  = LocalDateTime.now().plusMinutes(30).toString();
        String resp = enviar("CONTRATAR " + idTrabajador + " c-e3 \"Trabajo a calificar\" \"" + fin + "\"");
        System.out.println("  Contrato creado: " + resp);

        if (resp == null || !resp.startsWith("OK contrato:")) {
            System.out.println("  SKIP E3: no se pudo crear el contrato (trabajador ocupado?)");
            return;
        }
        String idContrato = resp.substring("OK contrato:".length()).trim();

        AtomicInteger resultado   = new AtomicInteger(0);
        CountDownLatch calInicio  = new CountDownLatch(1);

        // Hilo calificador: se bloquea esperando la finalización
        Thread hiloCalificar = new Thread(() -> {
            System.out.println("  [Calificador] Intentando calificar (se bloqueara hasta FINALIZAR)...");
            calInicio.countDown();
            String r = enviar("CALIFICAR " + idTrabajador + " " + idContrato + " 5 \"Excelente\"");
            System.out.println("  [Calificador] Respuesta recibida: " + r);
            resultado.set(r != null && r.startsWith("OK") ? 1 : -1);
        }, "HiloCalificador");
        hiloCalificar.setDaemon(true);
        hiloCalificar.start();

        calInicio.await();
        Thread.sleep(2000); // le damos tiempo al hilo de bloquearse

        System.out.println("  [Principal]   Enviando FINALIZAR ahora...");
        System.out.println("  [Principal]   " + enviar("FINALIZAR " + idTrabajador + " " + idContrato));

        hiloCalificar.join(5000);

        boolean ok = resultado.get() == 1;
        System.out.println("  Resultado E3 → " + (ok ? "CORRECTO: calificacion se desbloqueo al finalizar" : "ERROR: no se desbloqueo"));
        (ok ? totalOK : totalError).incrementAndGet();
    }

    // -------------------------------------------------------
    // Abre una conexión TCP, envía un comando y lee la respuesta
    // -------------------------------------------------------
    private static String enviar(String comando) {
        try (
            Socket     s   = new Socket(HOST, PUERTO);
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))
        ) {
            out.println(comando);
            return in.readLine();
        } catch (IOException e) {
            System.err.println("  [Error TCP] " + e.getMessage() + " | cmd: " + comando);
            return null;
        }
    }
}