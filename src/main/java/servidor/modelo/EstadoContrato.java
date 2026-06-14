package servidor.modelo;

/**
 * Ciclo de vida de un contrato: ACTIVO al crearse, FINALIZADO tras FINALIZAR o expiración.
 * Solo los contratos FINALIZADO habilitan el acquire() del semáforo de calificación.
 */
public enum EstadoContrato {
    ACTIVO("Activo"),
    FINALIZADO("Finalizado"),
    CANCELADO("Cancelado");

    private final String etiqueta;

    EstadoContrato(String etiqueta) {
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
