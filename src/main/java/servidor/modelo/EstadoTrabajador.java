package servidor.modelo;

public enum EstadoTrabajador {
    DISPONIBLE("Disponible"),
    EN_TRABAJO("En trabajo"),
    SUSPENDIDO("Suspendido");

    private final String etiqueta;

    EstadoTrabajador(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String getEtiqueta() {
        return etiqueta;
    }

    @Override
    public String toString() {
        return etiqueta;
    }
}
