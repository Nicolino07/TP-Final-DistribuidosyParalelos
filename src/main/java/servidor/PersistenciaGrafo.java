package servidor;

import servidor.modelo.GrafoTrabajadores;

import java.io.*;

/**
 * Persiste y recupera el grafo de trabajadores en disco mediante serialización Java.
 *
 * <p>El archivo {@code grafo.dat} se guarda en el directorio de trabajo al cerrar
 * la aplicación (ShutdownHook en {@link servidor.Main}) o ante el comando TCP GUARDAR.
 * Al iniciar, se carga automáticamente para restaurar el estado previo.
 *
 * <p>Los campos {@code transient} de {@link servidor.modelo.NodoTrabajador}
 * (lock, monitor, semáforo) se recrean durante la deserialización mediante
 * {@code readObject()}, garantizando que la concurrencia funcione correctamente
 * tras recargar desde disco.
 */
public class PersistenciaGrafo {

    private static final String ARCHIVO = "grafo.dat";

    // -------------------------------------------------------
    // Guarda el grafo completo a disco via serialización Java.
    // Se llama al cerrar la aplicación o ante comando GUARDAR.
    // -------------------------------------------------------
    public static void guardar(GrafoTrabajadores grafo) {
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(ARCHIVO))) {
            out.writeObject(grafo);
            System.out.println("[Persistencia] Grafo guardado en " + ARCHIVO);
        } catch (IOException e) {
            System.err.println("[Persistencia] Error al guardar: " + e.getMessage());
        }
    }

    // -------------------------------------------------------
    // Carga el grafo desde disco al iniciar la aplicación.
    // Si no existe el archivo devuelve un grafo vacío.
    // -------------------------------------------------------
    public static GrafoTrabajadores cargar() {
        File archivo = new File(ARCHIVO);
        if (!archivo.exists()) {
            System.out.println("[Persistencia] No existe " + ARCHIVO + ". Iniciando grafo vacío.");
            return new GrafoTrabajadores();
        }
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(archivo))) {
            GrafoTrabajadores grafo = (GrafoTrabajadores) in.readObject();
            System.out.println("[Persistencia] Grafo cargado desde " + ARCHIVO);
            return grafo;
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[Persistencia] Error al cargar: " + e.getMessage() + ". Iniciando grafo vacío.");
            return new GrafoTrabajadores();
        }
    }
}
