package servidor;

import servidor.modelo.GrafoTrabajadores;

import java.io.*;

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
