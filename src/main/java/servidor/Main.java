package servidor;

import servidor.modelo.GrafoTrabajadores;
import javax.swing.*;
import java.io.IOException;

/**
 * Clase Principal del Servidor.
 * Actúa como el punto de entrada que inicializa los componentes de
 * red, persistencia, hilos de fondo e interfaz gráfica del sistema.
 */
public class Main {
    // Puerto de red por el cual el servidor escuchará las conexiones de los clientes
    private static final int PUERTO = 9090;

    public static void main(String[] args) {

        GrafoTrabajadores grafo = PersistenciaGrafo.cargar();

        // Limpiar bench workers que pudieron haber quedado persistidos
        int eliminados = (int) grafo.listarTodos().stream()
            .map(n -> n.getTrabajador().getId())
            .filter(id -> id.startsWith("bench"))
            .filter(grafo::eliminar)
            .count();
        if (eliminados > 0) {
            System.out.println("[Main] Eliminados " + eliminados + " bench workers del grafo.");
            PersistenciaGrafo.guardar(grafo);
        }

        Object monitorExpiracion = new Object();

        HiloExpiracionContratos hiloExpiracion = new HiloExpiracionContratos(grafo, monitorExpiracion);
        hiloExpiracion.start();

        MetricasServidor metricas = new MetricasServidor();

        VentanaPrincipal ventana = new VentanaPrincipal(grafo, PUERTO);

        ServidorTCP servidor = new ServidorTCP(PUERTO, grafo, monitorExpiracion, ventana::log, metricas);
        try {
            servidor.iniciar();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null,
                    "No se pudo iniciar el servidor en el puerto " + PUERTO + ":\n" + e.getMessage(),
                    "Error de inicio", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        final int PUERTO_DASHBOARD = 9091;
        DashboardHTTP dashboard = new DashboardHTTP(metricas, grafo, PUERTO);
        try {
            dashboard.iniciar(PUERTO_DASHBOARD);
            ventana.log("[Dashboard] Disponible en http://localhost:" + PUERTO_DASHBOARD);
        } catch (IOException e) {
            ventana.log("[Dashboard] No se pudo iniciar en puerto " + PUERTO_DASHBOARD + ": " + e.getMessage());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            dashboard.detener();
            servidor.detener();
            hiloExpiracion.detener();
            PersistenciaGrafo.guardar(grafo);
            System.out.println("[Main] Grafo guardado. Cerrando.");
        }, "ShutdownHook"));

        SwingUtilities.invokeLater(() -> ventana.setVisible(true));
    }
}