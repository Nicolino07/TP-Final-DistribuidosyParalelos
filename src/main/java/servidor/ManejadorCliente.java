package servidor;

import servidor.modelo.*;

import java.io.*;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Maneja la sesión TCP de un cliente conectado.
 *
 * <p>Implementa {@link Runnable} para ejecutarse en un hilo dedicado por cliente
 * (patrón Thread-per-Connection). Cada instancia tiene su propio socket, por lo que
 * múltiples clientes se atienden en paralelo sin compartir estado entre sí.
 *
 * <p>El ciclo de vida es: leer línea → parsear con {@link ProtocoloParser} →
 * delegar al método ejecutarX() correspondiente → responder. El socket permanece
 * abierto hasta que el cliente lo cierra, permitiendo múltiples comandos por sesión.
 *
 * <p>Las operaciones de escritura (CONTRATAR, FINALIZAR, CALIFICAR) pueden bloquearse
 * mientras esperan locks o el semáforo de calificación — esto es intencional y
 * forma parte de la demostración de concurrencia.
 */
public class ManejadorCliente implements Runnable {

    private final Socket socket;
    private final GrafoTrabajadores grafo;
    private final Object monitorExpiracion;
    private final Consumer<String> log;
    private final MetricasServidor metricas;

    public ManejadorCliente(Socket socket, GrafoTrabajadores grafo, Object monitorExpiracion,
                            Consumer<String> log, MetricasServidor metricas) {
        this.socket = socket;
        this.grafo = grafo;
        this.monitorExpiracion = monitorExpiracion;
        this.log = log;
        this.metricas = metricas;
    }

    @Override
    public void run() {
        String addr = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        log.accept("[Cliente] Conectado: " + addr);
        try (
            BufferedReader in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter    out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)
        ) {
            String linea;
            while ((linea = in.readLine()) != null) {
                String respuesta = procesar(linea.trim());
                out.println(respuesta);
            }
        } catch (IOException e) {
            // conexion cerrada por el cliente
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            log.accept("[Cliente] Desconectado: " + addr);
        }
    }

    private String procesar(String linea) {
        ProtocoloParser.Comando cmd = ProtocoloParser.parsear(linea);
        long t0 = System.currentTimeMillis();
        String resp;
        try {
            resp = switch (cmd.getTipo()) {
                case REGISTRAR -> ejecutarRegistrar(cmd);
                case CONTRATAR -> ejecutarContratar(cmd);
                case FINALIZAR -> ejecutarFinalizar(cmd);
                case CALIFICAR -> ejecutarCalificar(cmd);
                case BUSCAR    -> ejecutarBuscar(cmd);
                case LISTAR    -> ejecutarListar();
                case OBTENER   -> ejecutarObtener(cmd);
                case STATS     -> ejecutarStats();
                case PING      -> "PONG";
                case GUARDAR   -> ejecutarGuardar();
                default        -> "ERROR comando desconocido";
            };
        } catch (Exception e) {
            resp = "ERROR " + e.getMessage();
        }
        metricas.registrar(cmd.getTipo().name(), !resp.startsWith("ERROR"), System.currentTimeMillis() - t0);
        return resp;
    }

    private String ejecutarRegistrar(ProtocoloParser.Comando cmd) {
        if (!cmd.tieneArgs(4))
            return "ERROR REGISTRAR requiere: <id> <nombre> <oficio> \"<descripcion>\"";
        Oficio oficio;
        try {
            oficio = Oficio.valueOf(cmd.getArg(2).toUpperCase());
        } catch (IllegalArgumentException e) {
            return "ERROR oficio invalido: " + cmd.getArg(2);
        }
        Trabajador t = new Trabajador(cmd.getArg(0), cmd.getArg(1), oficio, cmd.getArg(3));
        return grafo.registrar(t) ? "OK" : "ERROR ya existe un trabajador con id: " + cmd.getArg(0);
    }

    private String ejecutarContratar(ProtocoloParser.Comando cmd) {
        if (!cmd.tieneArgs(4))
            return "ERROR CONTRATAR requiere: <idTrabajador> <idConsumidor> \"<descripcion>\" \"<finEstimado>\"";
        LocalDateTime finEstimado;
        try {
            finEstimado = LocalDateTime.parse(cmd.getArg(3));
        } catch (DateTimeParseException e) {
            return "ERROR formato de fecha invalido. Use ISO: 2024-12-31T18:00:00";
        }
        try {
            Contrato contrato = new Contrato(cmd.getArg(1), cmd.getArg(0), cmd.getArg(2), finEstimado);
            grafo.contratar(cmd.getArg(0), contrato);
            synchronized (monitorExpiracion) {
                monitorExpiracion.notifyAll();
            }
            log.accept("[Contrato] " + cmd.getArg(1) + " contrató a " + cmd.getArg(0));
            return "OK contrato:" + contrato.getId();
        } catch (NodoTrabajador.TrabajadorOcupadoException | GrafoTrabajadores.TrabajadorNoEncontradoException e) {
            return "ERROR " + e.getMessage();
        }
    }

    private String ejecutarFinalizar(ProtocoloParser.Comando cmd) {
        if (!cmd.tieneArgs(2))
            return "ERROR FINALIZAR requiere: <idTrabajador> <idContrato>";
        try {
            grafo.finalizar(cmd.getArg(0), cmd.getArg(1));
            log.accept("[Contrato] Finalizado: " + cmd.getArg(1));
            return "OK";
        } catch (NodoTrabajador.ContratoInvalidoException | GrafoTrabajadores.TrabajadorNoEncontradoException e) {
            return "ERROR " + e.getMessage();
        }
    }

    private String ejecutarCalificar(ProtocoloParser.Comando cmd) {
        if (!cmd.tieneArgs(4))
            return "ERROR CALIFICAR requiere: <idTrabajador> <idContrato> <puntaje> \"<comentario>\"";
        int puntaje;
        try {
            puntaje = Integer.parseInt(cmd.getArg(2));
        } catch (NumberFormatException e) {
            return "ERROR puntaje debe ser un entero del 1 al 5";
        }
        try {
            NodoTrabajador nodo = grafo.obtener(cmd.getArg(0));
            Contrato contrato = nodo.getContratoActual();
            if (contrato == null)
                return "ERROR no hay contrato activo o finalizado para calificar";
            Calificacion cal = new Calificacion(contrato.getIdConsumidor(), cmd.getArg(1), puntaje, cmd.getArg(3));
            grafo.calificar(cmd.getArg(0), cal);
            log.accept("[Calificacion] " + cmd.getArg(0) + " recibio " + puntaje + " estrellas");
            return "OK " + String.format("%.1f", nodo.getPromedio());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR operacion interrumpida";
        } catch (NodoTrabajador.CalificacionInvalidaException | GrafoTrabajadores.TrabajadorNoEncontradoException e) {
            return "ERROR " + e.getMessage();
        }
    }

    private String ejecutarBuscar(ProtocoloParser.Comando cmd) {
        if (!cmd.tieneArgs(1))
            return "ERROR BUSCAR requiere: <oficio>";
        Oficio oficio;
        try {
            oficio = Oficio.valueOf(cmd.getArg(0).toUpperCase());
        } catch (IllegalArgumentException e) {
            return "ERROR oficio invalido: " + cmd.getArg(0);
        }
        List<NodoTrabajador> resultado = grafo.buscarPorOficio(oficio);
        if (resultado.isEmpty()) return "VACIO";

        StringBuilder sb = new StringBuilder();
        boolean esPrimero = true;

        for (NodoTrabajador n : resultado) {
            // Si el ID empieza con "bench", lo salteamos para que no ensucie el Top
            if (n.getTrabajador().getId().startsWith("bench")) {
                continue;
            }

            if (!esPrimero) {
                sb.append("  /  ");
            }
            sb.append(n.getTrabajador().getNombre())
                    .append(" (").append(n.getTrabajador().getId()).append(")")
                    .append(" * ").append(String.format("%.1f", n.getPromedio()));

            esPrimero = false;
        }

        // Si la lista tenía solo trabajadores de benchmark y quedó vacía, devolvemos VACIO
        return sb.isEmpty() ? "VACIO" : sb.toString();
    }

    private String ejecutarListar() {
        List<NodoTrabajador> lista = grafo.listarTodos();
        if (lista.isEmpty()) return "VACIO";
        StringBuilder sb = new StringBuilder();
        for (NodoTrabajador n : lista) {
            sb.append(n).append("\n");
        }
        return sb.toString().trim();
    }

    private String ejecutarObtener(ProtocoloParser.Comando cmd) {
        if (!cmd.tieneArgs(1))
            return "ERROR OBTENER requiere: <idTrabajador>";
        try {
            return grafo.obtener(cmd.getArg(0)).toString();
        } catch (GrafoTrabajadores.TrabajadorNoEncontradoException e) {
            return "ERROR " + e.getMessage();
        }
    }

    private String ejecutarStats() {
        return "Trabajadores: " + grafo.cantidadTrabajadores()
             + " | En trabajo: " + grafo.obtenerNodosEnTrabajo().size();
    }

    private String ejecutarGuardar() {
        PersistenciaGrafo.guardar(grafo);
        return "OK guardado";
    }
}
