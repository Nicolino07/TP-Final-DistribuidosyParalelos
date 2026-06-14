package servidor.modelo;

import java.io.Serializable;

/**
 * Datos inmutables de identidad de un trabajador (id, nombre, oficio, descripción).
 * No contiene lógica de concurrencia — eso es responsabilidad de {@link NodoTrabajador}.
 */
public class Trabajador implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;
    private String nombre;
    private Oficio oficio;
    private String descripcion;

    public Trabajador(String id, String nombre, Oficio oficio, String descripcion) {
        this.id = id;
        this.nombre = nombre;
        this.oficio = oficio;
        this.descripcion = descripcion;
    }

    public String getId() { return id; }
    public String getNombre() { return nombre; }
    public Oficio getOficio() { return oficio; }
    public String getDescripcion() { return descripcion; }

    public void setNombre(String nombre) { this.nombre = nombre; }
    public void setOficio(Oficio oficio) { this.oficio = oficio; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }

    @Override
    public String toString() {
        return "[" + id + "] " + nombre + " - " + oficio.getEtiqueta();
    }
}
