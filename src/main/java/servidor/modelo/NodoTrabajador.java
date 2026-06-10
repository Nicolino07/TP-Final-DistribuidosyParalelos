package servidor.modelo;


import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class NodoTrabajador implements Serializable {

    private static final long serialVersionUID = 1L;

    // Datos del trabajador
    private final Trabajador trabajador;
    private final List<Calificacion> calificaciones;
    private double promedio;
    private EstadoTrabajador estado;
    private Contrato contratoActual;

    // -------------------------------------------------------
    // Mecanismo 1: ReadWriteLock por nodo
    // Protege lectura y escritura de los datos del nodo.
    // Múltiples lecturas simultáneas permitidas.
    // Una escritura excluye a todos los demás.
    // -------------------------------------------------------
    private final transient ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // -------------------------------------------------------
    // Mecanismo 2: Monitor para el hilo de expiración
    // El hilo de expiración espera sobre este monitor.
    // Cuando se crea un contrato nuevo se notifica al hilo.
    // -------------------------------------------------------
    private final transient Object monitor = new Object();

    // -------------------------------------------------------
    // Mecanismo 3: Semáforo por contrato
    // Bloquea la calificación hasta que el contrato finaliza.
    // Se crea al contratar y se libera al finalizar.
    // -------------------------------------------------------
    private transient Semaphore semafороCalificacion;

    public NodoTrabajador(Trabajador trabajador) {
        this.trabajador = trabajador;
        this.calificaciones = new ArrayList<>();
        this.promedio = 0.0;
        this.estado = EstadoTrabajador.DISPONIBLE;
        this.contratoActual = null;
    }

    // -------------------------------------------------------
    // Escenario 1: contratar — writeLock
    // Solo un hilo puede contratar al trabajador a la vez.
    // Si ya está ocupado lanza excepción.
    // -------------------------------------------------------
    public void contratar(Contrato contrato) throws TrabajadorOcupadoException {
        lock.writeLock().lock();
        try {
            if (estado != EstadoTrabajador.DISPONIBLE) {
                throw new TrabajadorOcupadoException(
                        "El trabajador " + trabajador.getNombre() + " no está disponible. Estado: " + estado.getEtiqueta()
                );
            }
            this.contratoActual = contrato;
            this.estado = EstadoTrabajador.EN_TRABAJO;
            this.semafороCalificacion = new Semaphore(0); // bloqueado hasta finalizar

            // Notifica al hilo de expiración que hay un contrato nuevo
            synchronized (monitor) {
                monitor.notifyAll();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -------------------------------------------------------
    // Escenario 2: finalizar — writeLock + libera semáforo
    // Cambia estado a DISPONIBLE y habilita la calificación.
    // -------------------------------------------------------
    public void finalizar(String idContrato) throws ContratoInvalidoException {
        lock.writeLock().lock();
        try {
            if (contratoActual == null || !contratoActual.getId().equals(idContrato)) {
                throw new ContratoInvalidoException(
                        "El contrato " + idContrato + " no corresponde al contrato activo del trabajador."
                );
            }
            contratoActual.setEstado(EstadoContrato.FINALIZADO);
            this.estado = EstadoTrabajador.DISPONIBLE;

            // Libera el semáforo: ahora el consumidor puede calificar
            if (semafороCalificacion != null) {
                semafороCalificacion.release();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -------------------------------------------------------
    // Escenario 2: expirar — llamado por HiloExpiracionContratos
    // Mismo efecto que finalizar pero disparado por el sistema.
    // -------------------------------------------------------
    public void expirar() {
        lock.writeLock().lock();
        try {
            if (contratoActual != null && contratoActual.estaVencido()) {
                contratoActual.setEstado(EstadoContrato.FINALIZADO);
                this.estado = EstadoTrabajador.DISPONIBLE;
                if (semafороCalificacion != null) {
                    semafороCalificacion.release();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -------------------------------------------------------
    // Escenario 3: calificar — acquire del semáforo + writeLock
    // Bloquea hasta que el contrato esté finalizado.
    // Después toma el writeLock para modificar la lista.
    // -------------------------------------------------------
    public void calificar(Calificacion calificacion) throws InterruptedException, CalificacionInvalidaException {
        // Espera hasta que el contrato esté finalizado
        if (semafороCalificacion == null) {
            throw new CalificacionInvalidaException(
                    "No existe un contrato activo o finalizado para calificar."
            );
        }

        semafороCalificacion.acquire(); // bloquea si el contrato no finalizó

        lock.writeLock().lock();
        try {
            calificaciones.add(calificacion);
            recalcularPromedio();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -------------------------------------------------------
    // Lecturas — readLock
    // Múltiples hilos pueden leer en paralelo.
    // -------------------------------------------------------
    public Trabajador getTrabajador() {
        lock.readLock().lock();
        try {
            return trabajador;
        } finally {
            lock.readLock().unlock();
        }
    }

    public EstadoTrabajador getEstado() {
        lock.readLock().lock();
        try {
            return estado;
        } finally {
            lock.readLock().unlock();
        }
    }

    public double getPromedio() {
        lock.readLock().lock();
        try {
            return promedio;
        } finally {
            lock.readLock().unlock();
        }
    }

    // Copia defensiva — no exponemos la lista interna
    public List<Calificacion> getCalificaciones() {
        lock.readLock().lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(calificaciones));
        } finally {
            lock.readLock().unlock();
        }
    }

    public Contrato getContratoActual() {
        lock.readLock().lock();
        try {
            return contratoActual;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Object getMonitor() {
        return monitor;
    }

    // -------------------------------------------------------
    // Privado — recalcula el promedio al agregar calificación
    // Solo se llama desde dentro de un writeLock
    // -------------------------------------------------------
    private void recalcularPromedio() {
        if (calificaciones.isEmpty()) {
            promedio = 0.0;
            return;
        }
        promedio = calificaciones.stream()
                .mapToInt(Calificacion::getPuntaje)
                .average()
                .orElse(0.0);
    }

    @Override
    public String toString() {
        return trabajador.toString() + " | Promedio: " + String.format("%.1f", promedio) + " | " + estado.getEtiqueta();
    }

    // -------------------------------------------------------
    // Excepciones propias del nodo
    // -------------------------------------------------------
    public static class TrabajadorOcupadoException extends Exception {
        public TrabajadorOcupadoException(String mensaje) { super(mensaje); }
    }

    public static class ContratoInvalidoException extends Exception {
        public ContratoInvalidoException(String mensaje) { super(mensaje); }
    }

    public static class CalificacionInvalidaException extends Exception {
        public CalificacionInvalidaException(String mensaje) { super(mensaje); }
    }
}