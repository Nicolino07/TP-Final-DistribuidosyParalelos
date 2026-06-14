package servidor.modelo;

/**
 * Estado operativo de un trabajador en el grafo.
 * La transición DISPONIBLE → EN_TRABAJO la produce CONTRATAR (writeLock).
 * La vuelta a DISPONIBLE la producen FINALIZAR o la expiración automática del contrato.
 */
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
