package servidor.modelo;
import java.io.Serializable;
import java.time.LocalDateTime;

public class Calificacion implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String idConsumidor;
    private final String idContrato;
    private final int puntaje;
    private final String comentario;
    private final LocalDateTime fecha;

    public Calificacion(String idConsumidor, String idContrato, int puntaje, String comentario) {
        if (puntaje < 1 || puntaje > 5) {
            throw new IllegalArgumentException("El puntaje debe estar entre 1 y 5");
        }
        this.idConsumidor = idConsumidor;
        this.idContrato = idContrato;
        this.puntaje = puntaje;
        this.comentario = comentario;
        this.fecha = LocalDateTime.now();
    }

    public String getIdConsumidor() { return idConsumidor; }
    public String getIdContrato() { return idContrato; }
    public int getPuntaje() { return puntaje; }
    public String getComentario() { return comentario; }
    public LocalDateTime getFecha() { return fecha; }

    @Override
    public String toString() {
        return puntaje + "★ - " + comentario + " (" + idConsumidor + ")";
    }
}
