package servidor;

import servidor.modelo.GrafoTrabajadores;
import servidor.modelo.NodoTrabajador;

import java.util.List;

/**
 * Hilo daemon que expira contratos vencidos automáticamente.
 *
 * <p>Demuestra el patrón <b>Monitor (wait/notifyAll)</b>: el hilo duerme sobre
 * un objeto monitor compartido con el servidor TCP. Cuando llega un CONTRATAR,
 * {@link servidor.ManejadorCliente} llama {@code notifyAll()} para despertar
 * a este hilo antes del intervalo de espera, evitando busy-wait.
 *
 * <p>El flujo es:
 * <ol>
 *   <li>{@code monitor.wait(INTERVALO_MS)} — duerme hasta notificación o timeout.</li>
 *   <li>Itera los nodos en trabajo y llama {@code nodo.expirar()} si el contrato venció.</li>
 *   <li>Vuelve al paso 1.</li>
 * </ol>
 *
 * <p>{@code expirar()} a su vez libera el semáforo del nodo, lo que desbloquea
 * cualquier hilo que estuviera esperando en CALIFICAR.
 */
public class HiloExpiracionContratos extends Thread {

    // -------------------------------------------------------
    // Este hilo implementa el Escenario 2:
    // Revisa periódicamente los contratos activos y libera
    // al trabajador cuando el contrato vence.
    //
    // Mecanismo: Monitor con wait/notifyAll
    // El hilo duerme sobre el monitor del grafo.
    // Cuando se crea un contrato nuevo, ServidorTCP notifica
    // al hilo para que revise antes del intervalo normal.
    // -------------------------------------------------------

    private static final long INTERVALO_MS = 5000; // revisa cada 5 segundos

    private final GrafoTrabajadores grafo;
    private final Object monitor;
    private volatile boolean corriendo = true;

    public HiloExpiracionContratos(GrafoTrabajadores grafo, Object monitor) {
        this.grafo = grafo;
        this.monitor = monitor;
        setDaemon(true); // muere cuando muere la aplicación principal
        setName("HiloExpiracion");
    }

    @Override
    public void run() {
        System.out.println("[HiloExpiracion] Iniciado.");

        while (corriendo) {
            try {
                // -------------------------------------------------------
                // Espera sobre el monitor hasta que:
                // a) pasen INTERVALO_MS milisegundos, o
                // b) alguien llame notifyAll() al crear un contrato nuevo
                // -------------------------------------------------------
                synchronized (monitor) {
                    monitor.wait(INTERVALO_MS);
                }

                if (!corriendo) break;

                revisarContratos();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("[HiloExpiracion] Detenido.");
    }

    // -------------------------------------------------------
    // Revisa todos los nodos en trabajo y expira los vencidos
    // -------------------------------------------------------
    private void revisarContratos() {
        List<NodoTrabajador> enTrabajo = grafo.obtenerNodosEnTrabajo();

        for (NodoTrabajador nodo : enTrabajo) {
            var contrato = nodo.getContratoActual();
            if (contrato != null && contrato.estaVencido()) {
                nodo.expirar();
                System.out.println("[HiloExpiracion] Contrato vencido expirado: "
                    + contrato.getId()
                    + " | Trabajador: " + nodo.getTrabajador().getNombre()
                    + " → ahora DISPONIBLE");
            }
        }
    }

    public void detener() {
        corriendo = false;
        synchronized (monitor) {
            monitor.notifyAll(); // despierta al hilo para que pueda salir
        }
    }
}
