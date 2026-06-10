package servidor.modelo;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

public class Contrato implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final String idConsumidor;
    private final String idTrabajador;
    private final String descripcion;
    private final LocalDateTime inicio;
    private final LocalDateTime finEstimado;
    private EstadoContrato estado;

    public Contrato(String idConsumidor, String idTrabajador, String descripcion, LocalDateTime finEstimado) {
        this.id = UUID.randomUUID().toString();
        this.idConsumidor = idConsumidor;
        this.idTrabajador = idTrabajador;
        this.descripcion = descripcion;
        this.inicio = LocalDateTime.now();
        this.finEstimado = finEstimado;
        this.estado = EstadoContrato.ACTIVO;
    }

    public String getId() { return id; }
    public String getIdConsumidor() { return idConsumidor; }
    public String getIdTrabajador() { return idTrabajador; }
    public String getDescripcion() { return descripcion; }
    public LocalDateTime getInicio() { return inicio; }
    public LocalDateTime getFinEstimado() { return finEstimado; }
    public EstadoContrato getEstado() { return estado; }

    public void setEstado(EstadoContrato estado) { this.estado = estado; }

    public boolean estaVencido() {
        return estado == EstadoContrato.ACTIVO && LocalDateTime.now().isAfter(finEstimado);
    }

    @Override
    public String toString() {
        return "[" + id + "] " + idConsumidor + " → " + idTrabajador + " (" + estado.getEtiqueta() + ")";
    }
}
