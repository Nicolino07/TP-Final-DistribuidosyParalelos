package servidor;

import servidor.modelo.GrafoTrabajadores;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Consumer;
/**
 * Servidor de red basado en el protocolo TCP.
 * Aplica el patrón Multi-Threaded Connection Handler (un hilo por cliente)
 * para despachar peticiones de forma concurrente en segundo plano.
 */
public class ServidorTCP {

    private final int puerto;
    private final GrafoTrabajadores grafo;
    private final Object monitorExpiracion;
    private final Consumer<String> log;// Delegado (Lambda) para enviar mensajes de log a la GUI

    private ServerSocket serverSocket;
    private volatile boolean corriendo = false;

    public ServidorTCP(int puerto, GrafoTrabajadores grafo, Object monitorExpiracion, Consumer<String> log) {
        this.puerto = puerto;
        this.grafo = grafo;
        this.monitorExpiracion = monitorExpiracion;
        this.log = log;
    }

    // -------------------------------------------------------
    // Abre el ServerSocket y lanza el hilo aceptador.
    // Cada conexión entrante recibe su propio ManejadorCliente.
    // -------------------------------------------------------
    public void iniciar() throws IOException {
        serverSocket = new ServerSocket(puerto);
        corriendo = true;
        log.accept("[Servidor] Escuchando en puerto " + puerto);
        //  Hilo dedicado exclusivamente a recibir conexiones entrantes.
        // Evita que el hilo principal (GUI) se congele debido al método bloqueante accept().
        Thread aceptador = new Thread(() -> {
            while (corriendo) {
                try {
                    Socket cliente = serverSocket.accept();
                    Thread hiloCliente = new Thread(
                        new ManejadorCliente(cliente, grafo, monitorExpiracion, log)
                    );
                    hiloCliente.setDaemon(true);
                    hiloCliente.setName("Cliente-" + cliente.getPort());
                    hiloCliente.start();
                } catch (IOException e) {
                    if (corriendo) {
                        log.accept("[Servidor] Error aceptando conexion: " + e.getMessage());
                    }
                }
            }
        }, "ServidorTCP-Aceptador");
        aceptador.setDaemon(true);
        aceptador.start();
    }

    public void detener() {
        corriendo = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.accept("[Servidor] Error al cerrar: " + e.getMessage());
        }
        log.accept("[Servidor] Detenido.");
    }

    public int getPuerto() {
        return puerto;
    }
}