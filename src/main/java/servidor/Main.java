package servidor;

import servidor.modelo.GrafoTrabajadores;
import javax.swing.*;    //Para graficar
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

        // Intenta cargar el estado del grafo desde el disco rígido (grafo.dat).
        // Si el archivo no existe, inicializa un grafo nuevo y vacío en la memoria RAM.
        GrafoTrabajadores grafo = PersistenciaGrafo.cargar();

        // Monitor compartido entre ServidorTCP y el HiloExpiracionContratos
        Object monitorExpiracion = new Object();

        // Hilo de expiración de contratos (Escenario 2)
        // Corre en paralelo analizando de forma eficiente cuándo expiran los contratos activos.
        HiloExpiracionContratos hiloExpiracion = new HiloExpiracionContratos(grafo, monitorExpiracion);
        hiloExpiracion.start();

        // GUI — se crea antes del servidor para poder loguear desde el inicio
        VentanaPrincipal ventana = new VentanaPrincipal(grafo, PUERTO);

        // Servidor TCP
        ServidorTCP servidor = new ServidorTCP(PUERTO, grafo, monitorExpiracion, ventana::log);
        try {
            servidor.iniciar();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null,
                    "No se pudo iniciar el servidor en el puerto " + PUERTO + ":\n" + e.getMessage(),
                    "Error de inicio", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        // El 'ShutdownHook' es un hilo especial de Java que se ejecuta automáticamente
        // cuando la aplicación recibe una orden de cierre (x ej: cerrar la ventana).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            servidor.detener();
            hiloExpiracion.detener();
            PersistenciaGrafo.guardar(grafo);
            System.out.println("[Main] Grafo guardado. Cerrando.");
        }, "ShutdownHook"));

        SwingUtilities.invokeLater(() -> ventana.setVisible(true));
    }
}