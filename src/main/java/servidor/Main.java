package servidor;

import servidor.modelo.GrafoTrabajadores;

import javax.swing.*;
import java.io.IOException;

public class Main {

    private static final int PUERTO = 9090;

    public static void main(String[] args) {
        // Carga el grafo desde disco (o crea uno vacío si no existe)
        GrafoTrabajadores grafo = PersistenciaGrafo.cargar();

        // Monitor compartido entre ServidorTCP y HiloExpiracionContratos
        Object monitorExpiracion = new Object();

        // Hilo de expiración de contratos (Escenario 2)
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

        // Guarda el grafo al cerrar la aplicación
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            servidor.detener();
            hiloExpiracion.detener();
            PersistenciaGrafo.guardar(grafo);
            System.out.println("[Main] Grafo guardado. Cerrando.");
        }, "ShutdownHook"));

        SwingUtilities.invokeLater(() -> ventana.setVisible(true));
    }
}