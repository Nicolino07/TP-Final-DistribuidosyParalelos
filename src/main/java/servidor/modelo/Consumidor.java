package servidor.modelo;

import java.io.Serializable;

public class Consumidor implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;
    private String nombre;

    public Consumidor(String id, String nombre) {
        this.id = id;
        this.nombre = nombre;
    }

    public String getId() { return id; }
    public String getNombre() { return nombre; }

    public void setNombre(String nombre) { this.nombre = nombre; }

    @Override
    public String toString() {
        return "[" + id + "] " + nombre;
    }
}
