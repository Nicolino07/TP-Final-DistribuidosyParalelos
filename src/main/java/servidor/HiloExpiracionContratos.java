package com.tp.distribuidos.servidor;

import com.tp.distribuidos.servidor.modelo.GrafoTrabajadores;
import com.tp.distribuidos.servidor.modelo.NodoTrabajador;

import java.util.List;

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
