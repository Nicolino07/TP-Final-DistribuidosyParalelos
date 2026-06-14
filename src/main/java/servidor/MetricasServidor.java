package servidor;

import servidor.modelo.Contrato;
import servidor.modelo.GrafoTrabajadores;
import servidor.modelo.NodoTrabajador;
import servidor.modelo.Trabajador;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registra y expone métricas operativas del servidor en tiempo real.
 *
 * <p>Usa estructuras thread-safe ({@link java.util.concurrent.ConcurrentHashMap},
 * {@link java.util.concurrent.atomic.AtomicLong}) para acumular contadores de
 * requests OK/error y latencia promedio por tipo de comando, sin necesidad de locks.
 *
 * <p>Un hilo interno tick incrementa el historial de throughput cada segundo
 * (ventana de 60 puntos), que el dashboard grafica en tiempo real.
 * El método {@link #toJson(servidor.modelo.GrafoTrabajadores)} serializa todo el
 * estado a JSON para el endpoint {@code /metrics} del dashboard HTTP.
 */
public class MetricasServidor {

    private static final String[] TIPOS = {
        "BUSCAR","LISTAR","OBTENER","STATS","CONTRATAR","FINALIZAR","CALIFICAR","REGISTRAR","PING","GUARDAR","DESCONOCIDO"
    };

    private final long inicioMs = System.currentTimeMillis();
    private final Map<String, AtomicLong> ok     = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> err    = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sumLat = new ConcurrentHashMap<>();
    private final AtomicLong reqsSegActual = new AtomicLong(0);
    private final Deque<Long> historial    = new ArrayDeque<>();

    volatile boolean stressActivo = false;
    volatile int     stressHilos  = 0;

    public MetricasServidor() {
        for (String t : TIPOS) {
            ok.put(t,     new AtomicLong());
            err.put(t,    new AtomicLong());
            sumLat.put(t, new AtomicLong());
        }
        Thread tick = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                long n = reqsSegActual.getAndSet(0);
                synchronized (historial) {
                    historial.addLast(n);
                    if (historial.size() > 60) historial.pollFirst();
                }
            }
        }, "Metricas-Tick");
        tick.setDaemon(true);
        tick.start();
    }

    public void registrar(String tipo, boolean exito, long latMs) {
        reqsSegActual.incrementAndGet();
        ok.computeIfAbsent(tipo,  k -> new AtomicLong());
        err.computeIfAbsent(tipo, k -> new AtomicLong());
        sumLat.computeIfAbsent(tipo, k -> new AtomicLong());
        (exito ? ok : err).get(tipo).incrementAndGet();
        sumLat.get(tipo).addAndGet(latMs);
    }

    public String toJson(GrafoTrabajadores grafo) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"uptime\":").append((System.currentTimeMillis() - inicioMs) / 1000).append(",");

        appendConteos(sb, "ok",  ok);  sb.append(",");
        appendConteos(sb, "err", err); sb.append(",");

        sb.append("\"latProm\":{");
        boolean first = true;
        for (String t : TIPOS) {
            if (!first) sb.append(",");
            first = false;
            long total = ok.get(t).get() + err.get(t).get();
            double prom = total > 0 ? (double) sumLat.get(t).get() / total : 0.0;
            sb.append('"').append(t).append("\":").append(String.format(Locale.US, "%.1f", prom));
        }
        sb.append("},");

        sb.append("\"throughput\":[");
        synchronized (historial) {
            boolean f = true;
            for (long v : historial) { if (!f) sb.append(","); sb.append(v); f = false; }
        }
        sb.append("],");

        sb.append("\"trabajadores\":[");
        List<NodoTrabajador> lista = grafo.listarTodos();
        for (int i = 0; i < lista.size(); i++) {
            if (i > 0) sb.append(",");
            NodoTrabajador nodo = lista.get(i);
            Trabajador t = nodo.getTrabajador();
            Contrato c = nodo.getContratoActual();
            sb.append("{")
              .append("\"id\":\"").append(esc(t.getId())).append("\",")
              .append("\"nombre\":\"").append(esc(t.getNombre())).append("\",")
              .append("\"oficio\":\"").append(esc(t.getOficio().getEtiqueta())).append("\",")
              .append("\"oficioRaw\":\"").append(esc(t.getOficio().name())).append("\",")
              .append("\"descripcion\":\"").append(esc(t.getDescripcion())).append("\",")
              .append("\"estado\":\"").append(esc(nodo.getEstado().getEtiqueta())).append("\",")
              .append("\"promedio\":").append(String.format(Locale.US, "%.1f", nodo.getPromedio())).append(",")
              .append("\"cals\":").append(nodo.getCalificaciones().size()).append(",");
            if (c != null) {
                sb.append("\"contratoId\":\"").append(esc(c.getId())).append("\",")
                  .append("\"consumidor\":\"").append(esc(c.getIdConsumidor())).append("\",")
                  .append("\"finEstimado\":\"").append(c.getFinEstimado().toString()).append("\"");
            } else {
                sb.append("\"contratoId\":null,\"consumidor\":null,\"finEstimado\":null");
            }
            sb.append("}");
        }
        sb.append("],");

        sb.append("\"stressActivo\":").append(stressActivo).append(",");
        sb.append("\"stressHilos\":").append(stressHilos);
        sb.append("}");
        return sb.toString();
    }

    private void appendConteos(StringBuilder sb, String key, Map<String, AtomicLong> map) {
        sb.append('"').append(key).append("\":{");
        boolean first = true;
        for (String t : TIPOS) {
            if (!first) sb.append(",");
            first = false;
            sb.append('"').append(t).append("\":").append(map.get(t).get());
        }
        sb.append('}');
    }

    private String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
