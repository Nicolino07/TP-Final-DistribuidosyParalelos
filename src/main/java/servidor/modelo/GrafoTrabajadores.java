package servidor.modelo;


import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class GrafoTrabajadores implements Serializable {

    private static final long serialVersionUID = 1L;

    // ConcurrentHashMap — thread-safe para operaciones sobre la estructura del grafo.
    // La sincronización de los datos de cada nodo es responsabilidad de NodoTrabajador.
    private final ConcurrentHashMap<String, NodoTrabajador> nodos;

    public GrafoTrabajadores() {
        this.nodos = new ConcurrentHashMap<>();
    }

    // -------------------------------------------------------
    // Registrar un trabajador nuevo
    // putIfAbsent es atómica — si dos hilos intentan registrar
    // el mismo id al mismo tiempo, solo uno gana.
    // -------------------------------------------------------
    public boolean registrar(Trabajador trabajador) {
        NodoTrabajador nodo = new NodoTrabajador(trabajador);
        NodoTrabajador existente = nodos.putIfAbsent(trabajador.getId(), nodo);
        return existente == null; // true si se registró, false si ya existía
    }

    // -------------------------------------------------------
    // Contratar un trabajador — delega al nodo
    // -------------------------------------------------------
    public void contratar(String idTrabajador, Contrato contrato)
            throws NodoTrabajador.TrabajadorOcupadoException, TrabajadorNoEncontradoException {
        NodoTrabajador nodo = buscarNodo(idTrabajador);
        nodo.contratar(contrato);
    }

    // -------------------------------------------------------
    // Finalizar un contrato — delega al nodo
    // -------------------------------------------------------
    public void finalizar(String idTrabajador, String idContrato)
            throws NodoTrabajador.ContratoInvalidoException, TrabajadorNoEncontradoException {
        NodoTrabajador nodo = buscarNodo(idTrabajador);
        nodo.finalizar(idContrato);
    }

    // -------------------------------------------------------
    // Calificar un trabajador — delega al nodo
    // -------------------------------------------------------
    public void calificar(String idTrabajador, Calificacion calificacion)
            throws InterruptedException, NodoTrabajador.CalificacionInvalidaException, TrabajadorNoEncontradoException {
        NodoTrabajador nodo = buscarNodo(idTrabajador);
        nodo.calificar(calificacion);
    }

    // -------------------------------------------------------
    // Buscar trabajadores por oficio, ordenados por promedio
    // Lectura sobre el mapa — ConcurrentHashMap lo maneja solo
    // -------------------------------------------------------
    public List<NodoTrabajador> buscarPorOficio(Oficio oficio) {
        List<NodoTrabajador> resultado = new ArrayList<>();
        for (NodoTrabajador nodo : nodos.values()) {
            if (nodo.getTrabajador().getOficio() == oficio) {
                resultado.add(nodo);
            }
        }
        resultado.sort(Comparator.comparingDouble(NodoTrabajador::getPromedio).reversed());
        return resultado;
    }

    // -------------------------------------------------------
    // Listar todos los trabajadores ordenados por promedio
    // -------------------------------------------------------
    public List<NodoTrabajador> listarTodos() {
        List<NodoTrabajador> lista = new ArrayList<>(nodos.values());
        lista.sort(Comparator.comparingDouble(NodoTrabajador::getPromedio).reversed());
        return lista;
    }

    // -------------------------------------------------------
    // Obtener un nodo específico para la GUI o el servidor
    // -------------------------------------------------------
    public NodoTrabajador obtener(String idTrabajador) throws TrabajadorNoEncontradoException {
        return buscarNodo(idTrabajador);
    }

    // -------------------------------------------------------
    // Para el HiloExpiracionContratos — devuelve nodos activos
    // -------------------------------------------------------
    public List<NodoTrabajador> obtenerNodosEnTrabajo() {
        List<NodoTrabajador> activos = new ArrayList<>();
        for (NodoTrabajador nodo : nodos.values()) {
            if (nodo.getEstado() == EstadoTrabajador.EN_TRABAJO) {
                activos.add(nodo);
            }
        }
        return activos;
    }

    public int cantidadTrabajadores() {
        return nodos.size();
    }

    // -------------------------------------------------------
    // Privado — busca el nodo o lanza excepción
    // -------------------------------------------------------
    private NodoTrabajador buscarNodo(String id) throws TrabajadorNoEncontradoException {
        NodoTrabajador nodo = nodos.get(id);
        if (nodo == null) {
            throw new TrabajadorNoEncontradoException("No existe un trabajador con id: " + id);
        }
        return nodo;
    }

    // -------------------------------------------------------
    // Excepción propia del grafo
    // -------------------------------------------------------
    public static class TrabajadorNoEncontradoException extends Exception {
        public TrabajadorNoEncontradoException(String mensaje) { super(mensaje); }
    }
}