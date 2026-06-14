package servidor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import servidor.modelo.Contrato;
import servidor.modelo.GrafoTrabajadores;
import servidor.modelo.NodoTrabajador;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dashboard web en tiempo real para visualizar el stress test y el grafo de trabajadores.
 *
 * <p>Levanta un servidor HTTP embebido ({@code com.sun.net.httpserver.HttpServer})
 * en el puerto 9091 sin dependencias externas. Expone:
 * <ul>
 *   <li>{@code GET /}        — interfaz web completa (HTML/CSS/JS embebido en el jar).</li>
 *   <li>{@code GET /metrics} — estado del sistema en JSON, consumido por el dashboard cada segundo.</li>
 *   <li>{@code POST /api/cmd}          — proxy TCP: reenvía un comando al servidor en puerto 9090.</li>
 *   <li>{@code POST /api/stress/start} — inicia la simulación (10 lectores + 8 escritores).</li>
 *   <li>{@code POST /api/stress/stop}  — detiene la simulación.</li>
 * </ul>
 *
 * <p>La simulación de stress lanza hilos que ejecutan ciclos
 * CONTRATAR → espera → FINALIZAR → 20s pausa → CALIFICAR sobre los workers reales del grafo,
 * con selección aleatoria de worker y duración de contrato variable (30s / 1-2min / 5min).
 * Los errores de CONTRATAR (worker ocupado) se encolan y se envían al dashboard para
 * visualizarlos en el feed de eventos con chip rojo "OCUPADO".
 */
public class DashboardHTTP {

    private final MetricasServidor metricas;
    private final GrafoTrabajadores grafo;
    private final int puertoTCP;

    private HttpServer http;
    private final AtomicBoolean stressCorriendo = new AtomicBoolean(false);
    private ExecutorService stressPool;
    private final ConcurrentLinkedQueue<String> errorQueue = new ConcurrentLinkedQueue<>();


    public DashboardHTTP(MetricasServidor metricas, GrafoTrabajadores grafo, int puertoTCP) {
        this.metricas  = metricas;
        this.grafo     = grafo;
        this.puertoTCP = puertoTCP;
    }

    public void iniciar(int puertoHTTP) throws IOException {
        http = HttpServer.create(new InetSocketAddress(puertoHTTP), 0);
        http.createContext("/",                 ex -> handleRoot(ex));
        http.createContext("/metrics",          ex -> handleMetrics(ex));
        http.createContext("/api/cmd",          ex -> handleCmd(ex));
        http.createContext("/api/stress/start", ex -> handleStressStart(ex));
        http.createContext("/api/stress/stop",  ex -> handleStressStop(ex));
        http.setExecutor(Executors.newCachedThreadPool());
        http.start();
    }

    public void detener() {
        detenerStress();
        if (http != null) http.stop(0);
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private void handleRoot(HttpExchange ex) throws IOException {
        byte[] body = HTML.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private void handleMetrics(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
        StringBuilder errJson = new StringBuilder("[");
        String item; boolean first = true;
        while ((item = errorQueue.poll()) != null) {
            if (!first) errJson.append(",");
            errJson.append(item);
            first = false;
        }
        errJson.append("]");
        String base = metricas.toJson(grafo);
        String json = base.substring(0, base.length() - 1) + ",\"erroresStress\":" + errJson + "}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private void handleCmd(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
        String cmd  = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        String resp = enviarTCP(cmd);
        responderJson(ex, "{\"resp\":" + jsonStr(resp) + "}");
    }

    private void handleStressStart(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
        if (stressCorriendo.compareAndSet(false, true)) iniciarStress();
        responderJson(ex, "{\"ok\":true}");
    }

    private void handleStressStop(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
        detenerStress();
        responderJson(ex, "{\"ok\":true}");
    }

    // ── Stress E4.2 ──────────────────────────────────────────────────────────

    private void iniciarStress() {
        // Tomar los trabajadores reales del grafo (persistidos)
        String[] ids = grafo.listarTodos().stream()
            .map(n -> n.getTrabajador().getId())
            .toArray(String[]::new);

        if (ids.length == 0) {
            stressCorriendo.set(false);
            return;
        }

        // Liberar contratos activos que hayan quedado colgados
        for (String idT : ids) {
            try {
                NodoTrabajador nodo = grafo.obtener(idT);
                Contrato c = nodo.getContratoActual();
                if (c != null) grafo.finalizar(idT, c.getId());
            } catch (Exception ignored) {}
        }

        int lectores = 10, escritores = 8;
        stressPool = Executors.newFixedThreadPool(lectores + escritores);
        metricas.stressActivo = true;
        metricas.stressHilos  = lectores + escritores;

        // Lectores: consultas concurrentes lentas
        String[] cmdsLect = {
            "BUSCAR ALBANIL","BUSCAR ELECTRICISTA","BUSCAR PLOMERO","BUSCAR CARPINTERO",
            "LISTAR","STATS",
            "OBTENER " + ids[0],
            "OBTENER " + ids[Math.min(1, ids.length-1)],
            "LISTAR","STATS","BUSCAR ALBANIL","BUSCAR ELECTRICISTA"
        };
        for (int i = 0; i < lectores; i++) {
            final int idx = i;
            stressPool.submit(() -> {
                int pos = idx * 3;
                while (stressCorriendo.get()) {
                    enviarTCP(cmdsLect[pos++ % cmdsLect.length]);
                    try { Thread.sleep(800 + ThreadLocalRandom.current().nextLong(400)); }
                    catch (InterruptedException e) { return; }
                }
            });
        }

        // Escritores: ciclo CONTRATAR → espera → FINALIZAR → CALIFICAR sobre workers reales
        // Cada escritor rota por trabajadores distintos (offset por writerIdx) para evitar colisiones
        String[][] comentarios = {
            {},  // índice 0 — sin uso
            {"Pésimo trabajo, no lo recomiendo","No cumplió lo acordado","Muy mal resultado","Una decepción total"},
            {"Dejó bastante que desear","Tardó el doble de lo prometido","Resultado regular, no volvería a llamarlo"},
            {"Trabajo aceptable, nada especial","Cumplió pero sin destacarse","Más o menos, esperaba más"},
            {"Buen trabajo, lo recomiendo","Puntual y prolijo","Quedé conforme con el resultado"},
            {"Excelente, superó mis expectativas","Muy profesional, lo recomiendo sin dudas","Trabajo impecable, lo volvería a contratar"}
        };
        for (int i = 0; i < escritores; i++) {
            final int writerIdx = i;
            stressPool.submit(() -> {
                int ciclo = 0;
                while (stressCorriendo.get()) {
                    String idT = ids[ThreadLocalRandom.current().nextInt(ids.length)];
                    String idC = "usr-" + writerIdx + "-" + ciclo;
                    int durSeg = duracionAleatoria();
                    String fin = LocalDateTime.now().plusSeconds(durSeg).toString();

                    String r = enviarTCP(
                        String.format("CONTRATAR %s %s \"Trabajo benchmark\" \"%s\"", idT, idC, fin));

                    if (r != null && r.startsWith("OK contrato:")) {
                        String idCont = r.substring("OK contrato:".length()).trim();
                        // Esperar casi todo el contrato → worker visible como "En trabajo"
                        long esperaMs = Math.max(4000L, (durSeg - 5) * 1000L);
                        try { Thread.sleep(esperaMs); } catch (InterruptedException e) { return; }
                        if (!stressCorriendo.get()) return;
                        enviarTCP("FINALIZAR " + idT + " " + idCont);
                        // Pausa de 20s: semáforo liberado pero calificación pendiente
                        // → dashboard muestra transición observable: bloqueado → libre → ★
                        try { Thread.sleep(20_000); } catch (InterruptedException e) { return; }
                        if (!stressCorriendo.get()) return;
                        int pts = 1 + ThreadLocalRandom.current().nextInt(5);
                        String[] pool = comentarios[pts];
                        String com = pool[ThreadLocalRandom.current().nextInt(pool.length)];
                        enviarTCP(String.format("CALIFICAR %s %s %d \"%s\"", idT, idCont, pts, com));
                        ciclo++;
                    } else {
                        // Worker ocupado → registrar error y avanzar
                        try {
                            String nombre = grafo.obtener(idT).getTrabajador().getNombre();
                            errorQueue.offer("{\"id\":\"" + idT + "\",\"nombre\":\"" + nombre + "\"}");
                        } catch (Exception ignored) {}
                        ciclo++;
                        try { Thread.sleep(1000 + ThreadLocalRandom.current().nextLong(1000)); }
                        catch (InterruptedException e) { return; }
                    }
                    try { Thread.sleep(600 + ThreadLocalRandom.current().nextLong(900)); }
                    catch (InterruptedException e) { return; }
                }
            });
        }
    }

    // Distribución: 20% → 30s · 60% → 60-120s · 10% → 120-180s · 10% → 300s
    private static int duracionAleatoria() {
        int r = ThreadLocalRandom.current().nextInt(100);
        if (r < 20) return 30;
        if (r < 80) return 60  + ThreadLocalRandom.current().nextInt(61);
        if (r < 90) return 120 + ThreadLocalRandom.current().nextInt(61);
        return 300;
    }

    private void detenerStress() {
        stressCorriendo.set(false);
        metricas.stressActivo = false;
        metricas.stressHilos  = 0;
        if (stressPool != null) { stressPool.shutdownNow(); stressPool = null; }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String enviarTCP(String cmd) {
        try (
            Socket         s   = new Socket("localhost", puertoTCP);
            PrintWriter    out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true);
            BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream()))
        ) {
            out.println(cmd);
            return in.readLine();
        } catch (IOException e) {
            return "ERROR " + e.getMessage();
        }
    }

    private void responderJson(HttpExchange ex, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private void setCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\","\\\\").replace("\"","\\\"")
                       .replace("\n","\\n").replace("\r","") + "\"";
    }

    // ── HTML ─────────────────────────────────────────────────────────────────
    private static final String HTML = """
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Plataforma de Oficios — E4.2</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4/dist/chart.umd.min.js"></script>
<style>
:root {
  --bg:     #0d1117; --surf:  #161b22; --surf2: #1c2128;
  --border: #30363d; --text:  #e6edf3; --muted: #8b949e;
  --green:  #3fb950; --red:   #f85149; --yellow:#d29922;
  --blue:   #58a6ff; --purple:#bc8cff; --orange:#f0883e;
}
*{box-sizing:border-box;margin:0;padding:0}
body{background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;font-size:13px}

header{position:sticky;top:0;z-index:100;background:var(--surf);border-bottom:1px solid var(--border);padding:10px 20px;display:flex;align-items:center;gap:10px}
header h1{font-size:15px;font-weight:700}
.badge{padding:2px 10px;border-radius:20px;font-size:11px;font-weight:700}
.b-green {background:rgba(63,185,80,.15); color:var(--green); border:1px solid rgba(63,185,80,.3)}
.b-yellow{background:rgba(210,153,34,.15);color:var(--yellow);border:1px solid rgba(210,153,34,.3)}
#uptime{margin-left:auto;font-size:11px;font-family:monospace;color:var(--muted)}

main{display:flex;flex-direction:column;gap:14px;padding:14px;max-width:1400px;margin:0 auto}
.card{background:var(--surf);border:1px solid var(--border);border-radius:10px;padding:14px}
.card-title{font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.07em;color:var(--muted);margin-bottom:12px}

/* Charts */
.charts-row{display:grid;grid-template-columns:1.2fr 1fr;gap:14px}
.chart-wrap{position:relative;height:160px}

/* Stats */
.stats-row{display:grid;grid-template-columns:repeat(6,1fr);gap:8px;margin-top:10px}
.stat{background:var(--bg);border:1px solid var(--border);border-radius:6px;padding:8px;text-align:center}
.sv{font-size:20px;font-weight:700}.sl{font-size:10px;color:var(--muted);margin-top:2px}
.sv.green{color:var(--green)}.sv.red{color:var(--red)}.sv.blue{color:var(--blue)}.sv.yellow{color:var(--yellow)}

/* Stress */
.stress-row{display:flex;align-items:flex-start;gap:14px;flex-wrap:wrap}
.stress-toggle{padding:9px 22px;border-radius:8px;border:none;font-size:14px;font-weight:700;cursor:pointer;white-space:nowrap}
.stress-toggle.start{background:rgba(63,185,80,.15);color:var(--green);border:1px solid var(--green)}
.stress-toggle.stop {background:rgba(248,81,73,.15); color:var(--red);  border:1px solid var(--red)}
.stress-info{font-size:12px;color:var(--muted);line-height:1.8}
.stress-info em{color:var(--yellow);font-style:normal;font-weight:700}

/* Workers */
#workers-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(195px,1fr));gap:10px}
#workers-empty{padding:32px;text-align:center;color:var(--muted);font-size:14px;border:1px dashed var(--border);border-radius:8px;margin-top:4px}

.wcard{background:var(--surf2);border-radius:10px;padding:12px;border:1px solid var(--border);border-left:3px solid var(--border);transition:border-color .3s}
.wcard.disp  {border-left-color:var(--green)}
.wcard.ocupado{border-left-color:var(--yellow)}

@keyframes flashOrange{0%{box-shadow:0 0 0 0 rgba(240,136,62,0)}35%{box-shadow:0 0 0 7px rgba(240,136,62,.5)}100%{box-shadow:0 0 0 0 rgba(240,136,62,0)}}
@keyframes flashGreen {0%{box-shadow:0 0 0 0 rgba(63,185,80,0)} 35%{box-shadow:0 0 0 7px rgba(63,185,80,.6)} 100%{box-shadow:0 0 0 0 rgba(63,185,80,0)}}
.anim-c{animation:flashOrange .8s ease-out}
.anim-k{animation:flashGreen  .9s ease-out}

.wc-head{display:flex;gap:8px;align-items:center;margin-bottom:8px}
.wc-av{width:32px;height:32px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:14px;font-weight:700;flex-shrink:0;background:rgba(88,166,255,.1);color:var(--blue)}
.wcard.ocupado .wc-av{background:rgba(240,136,62,.15);color:var(--orange)}
.wc-nombre{font-weight:700;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.wc-id    {font-family:monospace;font-size:10px;color:var(--muted)}
.wc-oficio{font-size:10px;font-weight:700;color:var(--blue);margin-top:1px}

.wc-stars{font-size:17px;color:var(--yellow);letter-spacing:1px;margin:7px 0 1px;transition:all .3s}
.wc-prom {font-size:11px;color:var(--muted);margin-bottom:7px}

.wc-estado-row{display:flex;align-items:center;gap:5px;margin-bottom:3px}
.wc-dot{width:7px;height:7px;border-radius:50%;flex-shrink:0}
.wc-dot.disp{background:var(--green)}
.wc-dot.ocup{background:var(--yellow);animation:pulse 1.4s infinite}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.25}}
.wc-label{font-size:11px;font-weight:700}
.wc-label.disp{color:var(--green)}.wc-label.ocup{color:var(--yellow)}

.wc-by   {font-size:10px;color:var(--muted);font-family:monospace;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.wc-timer{font-size:10px;font-family:monospace;color:var(--blue);font-weight:700}

.wc-mechs{margin-top:9px;padding-top:8px;border-top:1px solid var(--border);display:flex;flex-direction:column;gap:5px}
.wc-mechs-hdr{font-size:9px;font-weight:700;text-transform:uppercase;letter-spacing:.06em;color:var(--muted);margin-bottom:2px}
.wm-row{display:flex;align-items:center;gap:6px}
.wm-name{font-size:10px;font-weight:700;flex:1}
.wm-name.rw {color:var(--blue)}
.wm-name.sem{color:var(--muted)}
.wm-name.sem.on{color:var(--yellow)}
.wm-name.mon{color:var(--muted)}
.wm-name.mon.on{color:var(--purple)}
.wm-badge{font-size:9px;font-weight:700;padding:2px 7px;border-radius:4px;white-space:nowrap}
.wm-badge.rw   {background:rgba(88,166,255,.15);color:var(--blue)}
.wm-badge.sem-libre{background:rgba(255,255,255,.06);color:var(--muted)}
.wm-badge.sem-on   {background:rgba(210,153,34,.25);color:var(--yellow);animation:badgePulse 1.2s infinite}
.wm-badge.mon-libre{background:rgba(255,255,255,.06);color:var(--muted)}
.wm-badge.mon-on   {background:rgba(188,140,255,.2); color:var(--purple)}
@keyframes badgePulse{0%,100%{opacity:1}50%{opacity:.55}}
.wc-lastev{margin-top:8px;padding-top:7px;border-top:1px solid var(--border);font-size:9px;color:var(--muted);min-height:12px}
.lev-row1{display:flex;align-items:center;gap:5px;margin-bottom:2px}
.lev-chip{font-size:8px;font-weight:700;padding:1px 5px;border-radius:3px;flex-shrink:0}
.lev-chip.c{background:rgba(240,136,62,.2);color:var(--orange)}
.lev-chip.f{background:rgba(87,242,135,.12);color:var(--green)}
.lev-chip.k{background:rgba(210,153,34,.2);color:var(--yellow)}
.lev-ts{font-family:monospace;opacity:.5}
.lev-det{line-height:1.45;word-break:break-word}

/* Events */
#events-feed{max-height:260px;overflow-y:auto;display:flex;flex-direction:column;gap:3px}
.ev{display:flex;align-items:baseline;gap:8px;padding:5px 8px;border-radius:6px;font-size:12px;animation:fadeIn .3s ease}
@keyframes fadeIn{from{opacity:0;transform:translateY(-4px)}to{opacity:1;transform:none}}
.ev.ev-c{background:rgba(240,136,62,.08);border-left:3px solid var(--orange)}
.ev.ev-k{background:rgba(63,185,80,.08); border-left:3px solid var(--green)}
.ev.ev-f{background:rgba(139,148,158,.06);border-left:3px solid var(--muted)}
.ev.ev-e{background:rgba(248,81,73,.07);  border-left:3px solid var(--red)}
.ev-ts  {font-family:monospace;font-size:10px;color:var(--muted);flex-shrink:0}
.ev-tipo{font-weight:700;flex-shrink:0;min-width:72px}
.ev-tipo.c{color:var(--orange)}.ev-tipo.k{color:var(--green)}.ev-tipo.f{color:var(--muted)}.ev-tipo.e{color:var(--red)}
.ev-det {color:var(--text);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}

/* TCP panel */
.cmd-btns{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:12px}
.btn-cmd{padding:5px 11px;border-radius:6px;border:1px solid var(--border);background:var(--surf2);color:var(--text);font-size:11px;font-weight:700;cursor:pointer;transition:border-color .15s}
.btn-cmd:hover{border-color:var(--blue);color:var(--blue)}
.cmd-forms{display:flex;flex-direction:column;gap:8px;margin-bottom:10px}
.cmd-form{display:flex;flex-wrap:wrap;align-items:center;gap:6px;padding:8px 10px;background:var(--surf2);border-radius:8px;border:1px solid var(--border)}
.cmd-form-label{font-size:10px;font-weight:700;color:var(--muted);text-transform:uppercase;letter-spacing:.05em;min-width:68px}
.cmd-fi{padding:5px 8px;background:var(--bg);border:1px solid var(--border);border-radius:6px;color:var(--text);font-size:11px;min-width:0}
.cmd-fi:focus{outline:none;border-color:var(--blue)}
.cmd-fi.grow{flex:1}
.btn-exec{padding:5px 12px;border-radius:6px;border:none;background:var(--blue);color:#0d1117;font-size:11px;font-weight:700;cursor:pointer}
.raw-resp{margin-top:8px;padding:8px 10px;border-radius:6px;font-family:monospace;font-size:12px;background:var(--bg);border:1px solid var(--border);min-height:38px;white-space:pre-wrap;color:var(--muted)}
.raw-resp.ok {border-color:var(--green);color:var(--green)}
.raw-resp.err{border-color:var(--red);  color:var(--red)}

@media(max-width:700px){.charts-row{grid-template-columns:1fr}.stats-row{grid-template-columns:repeat(3,1fr)}}
</style>
</head>
<body>
<header>
  <h1>Plataforma de Oficios</h1>
  <span class="badge b-green">TCP :9090</span>
  <span id="stress-badge" class="badge b-yellow" style="display:none">⚡ E4.2 ACTIVO</span>
  <span id="uptime">uptime: --</span>
</header>

<main>

<!-- MÉTRICAS ─────────────────────────────────────────────────────── -->
<div class="charts-row">
  <div class="card">
    <div class="card-title">Operaciones por tipo</div>
    <div class="chart-wrap"><canvas id="chart-bar"></canvas></div>
  </div>
  <div class="card">
    <div class="card-title">Throughput (req / s)</div>
    <div class="chart-wrap"><canvas id="chart-thru"></canvas></div>
  </div>
</div>

<!-- STATS ────────────────────────────────────────────────────────── -->
<div class="card" style="padding:10px 14px">
  <div class="stats-row">
    <div class="stat"><div class="sv blue"   id="s-total">0</div><div class="sl">Total req</div></div>
    <div class="stat"><div class="sv green"  id="s-ok">0</div><div class="sl">OK</div></div>
    <div class="stat"><div class="sv red"    id="s-err">0</div><div class="sl">Errores</div></div>
    <div class="stat"><div class="sv yellow" id="s-rps">0</div><div class="sl">req/s</div></div>
    <div class="stat"><div class="sv blue"   id="s-contratos">0</div><div class="sl">Contratos</div></div>
    <div class="stat"><div class="sv green"  id="s-califs">0</div><div class="sl">Calificaciones</div></div>
  </div>
</div>

<!-- SIMULACIÓN ───────────────────────────────────────────────────── -->
<div class="card">
  <div class="card-title">Escenario 4.2 — Concurrencia controlada</div>
  <div class="stress-row">
    <button id="stress-btn" class="stress-toggle start" onclick="toggleStress()">▶ Iniciar simulación</button>
    <div class="stress-info">
      <em>10 lectores</em> · BUSCAR / LISTAR / STATS · ~1 seg entre solicitudes<br>
      <em>8 escritores</em> · CONTRATAR → espera → FINALIZAR → CALIFICAR · calificación aleatoria 1-5 ★<br>
      Duraciones: <em>30 s</em> (20 %) · <em>1-2 min</em> (60 %) · <em>2-3 min</em> (10 %) · <em>5 min</em> (10 %)
    </div>
  </div>
</div>

<!-- TRABAJADORES ─────────────────────────────────────────────────── -->
<div class="card">
  <div class="card-title">Trabajadores en tiempo real</div>
  <div id="workers-grid"></div>
  <div id="workers-empty">Iniciá la simulación para ver a los trabajadores en acción</div>
</div>

<!-- EVENTOS ──────────────────────────────────────────────────────── -->
<div class="card">
  <div class="card-title">Eventos recientes</div>
  <div id="events-feed"><div style="text-align:center;color:var(--muted);padding:18px;font-size:12px">Sin eventos aún — iniciá la simulación</div></div>
</div>

<!-- TCP RAW ──────────────────────────────────────────────────────── -->
<div class="card">
  <div class="card-title">Comando TCP directo</div>

  <!-- Lectura rápida -->
  <div class="cmd-btns">
    <button class="btn-cmd" onclick="enviarCmd('PING')">PING</button>
    <button class="btn-cmd" onclick="enviarCmd('STATS')">STATS</button>
    <button class="btn-cmd" onclick="enviarCmd('LISTAR')">LISTAR</button>
    <button class="btn-cmd" onclick="enviarCmd('GUARDAR')">GUARDAR</button>
    <button class="btn-cmd" onclick="enviarCmd('BUSCAR ALBANIL')">BUSCAR ALBANIL</button>
    <button class="btn-cmd" onclick="enviarCmd('BUSCAR ELECTRICISTA')">BUSCAR ELECTRICISTA</button>
    <button class="btn-cmd" onclick="enviarCmd('BUSCAR PLOMERO')">BUSCAR PLOMERO</button>
    <button class="btn-cmd" onclick="enviarCmd('BUSCAR CARPINTERO')">BUSCAR CARPINTERO</button>
    <button class="btn-cmd" onclick="enviarCmd('BUSCAR MAESTRO')">BUSCAR MAESTRO</button>
    <button class="btn-cmd" onclick="enviarCmd('BUSCAR PROFESOR')">BUSCAR PROFESOR</button>
    <select class="cmd-fi" id="sel-obtener" onchange="enviarCmd('OBTENER '+this.value);this.selectedIndex=0">
      <option value="">OBTENER worker…</option>
    </select>
  </div>

  <!-- Formularios de escritura -->
  <div class="cmd-forms">

    <!-- REGISTRAR -->
    <div class="cmd-form">
      <span class="cmd-form-label">REGISTRAR</span>
      <input class="cmd-fi" id="r-id"     placeholder="id (ej: pe-01)" style="width:90px">
      <input class="cmd-fi" id="r-nombre" placeholder="nombre" style="width:90px">
      <select class="cmd-fi" id="r-oficio">
        <option>ALBANIL</option><option>ELECTRICISTA</option><option>PLOMERO</option>
        <option>CARPINTERO</option><option>MAESTRO</option><option>PROFESOR</option>
      </select>
      <input class="cmd-fi grow" id="r-desc" placeholder="descripción">
      <button class="btn-exec" onclick="ejecutarRegistrar()">▶</button>
    </div>

    <!-- CONTRATAR -->
    <div class="cmd-form">
      <span class="cmd-form-label">CONTRATAR</span>
      <select class="cmd-fi" id="c-worker"><option value="">worker disponible…</option></select>
      <input class="cmd-fi" id="c-consumer" placeholder="tu nombre" style="width:100px">
      <select class="cmd-fi" id="c-dur">
        <option value="1">1 min</option>
        <option value="5">5 min</option>
        <option value="30">30 min</option>
        <option value="60">1 hora</option>
      </select>
      <button class="btn-exec" onclick="ejecutarContratar()">▶</button>
    </div>

    <!-- FINALIZAR -->
    <div class="cmd-form">
      <span class="cmd-form-label">FINALIZAR</span>
      <select class="cmd-fi grow" id="f-worker"><option value="">worker en trabajo…</option></select>
      <button class="btn-exec" onclick="ejecutarFinalizar()">▶</button>
    </div>

    <!-- CALIFICAR -->
    <div class="cmd-form">
      <span class="cmd-form-label">CALIFICAR</span>
      <select class="cmd-fi grow" id="k-worker"><option value="">worker a calificar…</option></select>
      <select class="cmd-fi" id="k-stars">
        <option value="5">★★★★★</option><option value="4">★★★★☆</option>
        <option value="3">★★★☆☆</option><option value="2">★★☆☆☆</option>
        <option value="1">★☆☆☆☆</option>
      </select>
      <input class="cmd-fi grow" id="k-comment" placeholder="comentario (opcional)">
      <button class="btn-exec" onclick="ejecutarCalificar()">▶</button>
    </div>

  </div>
  <div class="raw-resp" id="raw-resp">— esperando —</div>
</div>

</main>
<script>
// ── Charts ───────────────────────────────────────────────────────────────
const TIPOS = ['BUSCAR','LISTAR','OBTENER','STATS','CONTRATAR','FINALIZAR','CALIFICAR','REGISTRAR','PING','GUARDAR','DESCONOCIDO'];

const chartBar = new Chart(document.getElementById('chart-bar').getContext('2d'), {
  type:'bar',
  data:{
    labels:TIPOS,
    datasets:[
      {label:'OK',    data:Array(TIPOS.length).fill(0),
       backgroundColor:['#3fb950','#3fb950','#3fb950','#3fb950','#58a6ff','#bc8cff','#d29922','#f0883e','#3fb950','#3fb950','#f85149'],
       borderRadius:3},
      {label:'Error', data:Array(TIPOS.length).fill(0),
       backgroundColor:'rgba(248,81,73,.5)', borderRadius:3}
    ]
  },
  options:{
    responsive:true,maintainAspectRatio:false,
    scales:{
      x:{ticks:{color:'#8b949e',font:{size:9}},grid:{color:'#21262d'}},
      y:{ticks:{color:'#8b949e'},grid:{color:'#21262d'},beginAtZero:true}
    },
    plugins:{legend:{labels:{color:'#e6edf3',font:{size:11}}}},
    animation:{duration:0}
  }
});

const chartLine = new Chart(document.getElementById('chart-thru').getContext('2d'), {
  type:'line',
  data:{
    labels:Array(60).fill(''),
    datasets:[{label:'req/s',data:Array(60).fill(0),
      borderColor:'#58a6ff',backgroundColor:'rgba(88,166,255,.08)',
      fill:true,tension:.4,pointRadius:0,borderWidth:2}]
  },
  options:{
    responsive:true,maintainAspectRatio:false,
    scales:{x:{display:false},y:{ticks:{color:'#8b949e'},grid:{color:'#21262d'},beginAtZero:true}},
    plugins:{legend:{labels:{color:'#e6edf3',font:{size:11}}}},
    animation:{duration:0}
  }
});

// ── Estado ───────────────────────────────────────────────────────────────
let stressActivo = false;
let prevW    = {};  // id → {estado, cals, promedio}
let wEls     = {};  // id → DOM card
let eventos  = [];
let lastEvW  = {};  // id → {tipo, detalle, ts}

// ── Poll ─────────────────────────────────────────────────────────────────
function poll() {
  fetch('/metrics').then(r => r.json()).then(d => {
    // Uptime
    const h=Math.floor(d.uptime/3600), m=Math.floor((d.uptime%3600)/60), s=d.uptime%60;
    document.getElementById('uptime').textContent =
      'uptime: '+h+'h '+String(m).padStart(2,'0')+'m '+String(s).padStart(2,'0')+'s';

    // Charts
    chartBar.data.datasets[0].data = TIPOS.map(t => d.ok[t]  || 0);
    chartBar.data.datasets[1].data = TIPOS.map(t => d.err[t] || 0);
    chartBar.update('none');
    if (d.throughput && d.throughput.length) {
      const pad = Array(Math.max(0, 60-d.throughput.length)).fill(0);
      chartLine.data.datasets[0].data = [...pad, ...d.throughput];
      chartLine.update('none');
    }

    // Stats
    const totOk  = TIPOS.reduce((s,t) => s+(d.ok[t]||0),  0);
    const totErr = TIPOS.reduce((s,t) => s+(d.err[t]||0), 0);
    const rps = d.throughput&&d.throughput.length ? d.throughput[d.throughput.length-1] : 0;
    document.getElementById('s-total').textContent     = (totOk+totErr).toLocaleString('es-AR');
    document.getElementById('s-ok').textContent        = totOk.toLocaleString('es-AR');
    document.getElementById('s-err').textContent       = totErr.toLocaleString('es-AR');
    document.getElementById('s-rps').textContent       = rps;
    document.getElementById('s-contratos').textContent = (d.ok['CONTRATAR']||0).toLocaleString('es-AR');
    document.getElementById('s-califs').textContent    = (d.ok['CALIFICAR']||0).toLocaleString('es-AR');

    // Workers
    if (d.trabajadores && d.trabajadores.length) {
      document.getElementById('workers-empty').style.display = 'none';
      detectarCambios(d.trabajadores);
      renderWorkers(d.trabajadores);
      actualizarSelectorWorkers(d.trabajadores);
    }

    // Errores de stress (CONTRATAR rechazados)
    if (d.erroresStress && d.erroresStress.length) {
      d.erroresStress.forEach(e => {
        addEv('e', e.nombre, e.id, '🔒 RWLock(write) bloqueado — trabajador ya ocupado, contratación rechazada');
      });
    }

    // Stress badge
    if (d.stressActivo !== stressActivo) { stressActivo = d.stressActivo; syncStressUI(); }
    document.getElementById('stress-badge').style.display = d.stressActivo ? '' : 'none';
  }).catch(()=>{});
  setTimeout(poll, 1000);
}
poll();

// ── Workers ──────────────────────────────────────────────────────────────
function renderWorkers(workers) {
  const grid = document.getElementById('workers-grid');
  workers.forEach(w => {
    if (!wEls[w.id]) {
      const card = document.createElement('div');
      card.innerHTML =
        '<div class="wc-head">' +
          '<div class="wc-av" data-av></div>' +
          '<div style="min-width:0">' +
            '<div class="wc-nombre"></div>' +
            '<div class="wc-id"></div>' +
            '<div class="wc-oficio"></div>' +
          '</div>' +
        '</div>' +
        '<div class="wc-stars"></div>' +
        '<div class="wc-prom"></div>' +
        '<div class="wc-estado-row"><span class="wc-dot"></span><span class="wc-label"></span></div>' +
        '<div class="wc-by"></div>' +
        '<div class="wc-timer"></div>' +
        '<div class="wc-mechs">' +
          '<div class="wc-mechs-hdr">Mecanismos de concurrencia</div>' +
          '<div class="wm-row">' +
            '<span class="wm-name rw">🔒 ReadWriteLock</span>' +
            '<span class="wm-badge rw">SIEMPRE</span>' +
          '</div>' +
          '<div class="wm-row">' +
            '<span class="wm-name sem" data-sem-name></span>' +
            '<span class="wm-badge sem-libre" data-sem-badge></span>' +
          '</div>' +
          '<div class="wm-row">' +
            '<span class="wm-name mon" data-mon-name></span>' +
            '<span class="wm-badge mon-libre" data-mon-badge></span>' +
          '</div>' +
        '</div>' +
        '<div class="wc-lastev" data-lastev></div>';
      card.className = 'wcard';
      card.querySelector('[data-av]').textContent = w.nombre.charAt(0).toUpperCase();
      grid.appendChild(card);
      wEls[w.id] = card;
    }
    actualizarCard(wEls[w.id], w);
  });
}

function actualizarCard(card, w) {
  const disp = w.estado === 'Disponible';
  const prev = prevW[w.id] || {};

  // Animaciones
  if (prev.estado && prev.estado !== w.estado && !disp) {
    card.classList.remove('anim-c','anim-k');
    void card.offsetWidth;
    card.classList.add('anim-c');
    setTimeout(() => card.classList.remove('anim-c'), 800);
  }
  if (typeof prev.cals === 'number' && prev.cals < w.cals) {
    card.classList.remove('anim-k');
    void card.offsetWidth;
    card.classList.add('anim-k');
    setTimeout(() => card.classList.remove('anim-k'), 900);
  }

  card.className = 'wcard ' + (disp ? 'disp' : 'ocupado');
  card.querySelector('.wc-nombre').textContent = w.nombre;
  card.querySelector('.wc-id').textContent     = w.id;
  card.querySelector('.wc-oficio').textContent = w.oficio;

  card.querySelector('.wc-stars').textContent  = estrellas(w.promedio);
  card.querySelector('.wc-prom').textContent   = w.cals > 0
    ? (+w.promedio).toFixed(1) + ' ★ · ' + w.cals + ' cal' + (w.cals!==1?'s':'') : 'Sin calificaciones';

  const dot   = card.querySelector('.wc-dot');
  const label = card.querySelector('.wc-label');
  dot.className   = 'wc-dot '   + (disp ? 'disp' : 'ocup');
  label.className = 'wc-label ' + (disp ? 'disp' : 'ocup');
  label.textContent = disp ? 'Disponible' : 'En trabajo';

  card.querySelector('.wc-by').textContent    = (!disp && w.consumidor) ? '← ' + w.consumidor : '';
  card.querySelector('.wc-timer').textContent = (!disp && w.finEstimado) ? countdown(w.finEstimado) : '';

  // Semáforo: bloqueado (acquire pendiente) cuando hay contrato activo
  const semName  = card.querySelector('[data-sem-name]');
  const semBadge = card.querySelector('[data-sem-badge]');
  if (disp) {
    semName.textContent  = '🚦 Semáforo';
    semName.className    = 'wm-name sem';
    semBadge.textContent = 'libre';
    semBadge.className   = 'wm-badge sem-libre';
  } else {
    semName.textContent  = '🚦 Semáforo';
    semName.className    = 'wm-name sem on';
    semBadge.textContent = '▓ BLOQUEADO';
    semBadge.className   = 'wm-badge sem-on';
  }

  // Monitor: activo (HiloExpiración esperando vencimiento) cuando hay contrato activo
  const monName  = card.querySelector('[data-mon-name]');
  const monBadge = card.querySelector('[data-mon-badge]');
  if (disp) {
    monName.textContent  = '📡 Monitor';
    monName.className    = 'wm-name mon';
    monBadge.textContent = 'reposo';
    monBadge.className   = 'wm-badge mon-libre';
  } else {
    monName.textContent  = '📡 Monitor';
    monName.className    = 'wm-name mon on';
    monBadge.textContent = '⏱ ACTIVO';
    monBadge.className   = 'wm-badge mon-on';
  }
}

function estrellas(p) {
  const n = Math.round(+p || 0);
  return '★'.repeat(n) + '☆'.repeat(Math.max(0,5-n));
}

function countdown(fin) {
  const ms = new Date(fin) - Date.now();
  if (ms <= 0) return 'vencido';
  const s = Math.floor(ms/1000);
  if (s < 60) return s + 's';
  return Math.floor(s/60) + 'm ' + String(s%60).padStart(2,'0') + 's';
}

// ── Detección de cambios → eventos ───────────────────────────────────────
function detectarCambios(workers) {
  workers.forEach(w => {
    const p = prevW[w.id];
    if (p) {
      let tipo = null, detalle = null;
      if (p.estado !== w.estado) {
        if (w.estado !== 'Disponible') {
          tipo    = 'c';
          detalle = 'por ' + (w.consumidor||'?') +
            ' — 🔒 RWLock(write) adquirido · 🚦 Semáforo(0) iniciado · 📡 Monitor notificado';
        } else {
          tipo    = 'f';
          detalle = '🔒 RWLock(write) · 🚦 Semáforo.release() — calificación habilitada · 📡 Monitor en espera';
        }
      } else if (p.cals < w.cals) {
        tipo    = 'k';
        detalle = estrellas(w.promedio) + ' ' + (+w.promedio).toFixed(1) +
          ' — 🚦 Semáforo.acquire() OK · 🔒 RWLock(write) · promedio recalculado';
      }
      if (tipo) {
        addEv(tipo, w.nombre, w.id, detalle);
        const now = new Date();
        const ts  = [now.getHours(),now.getMinutes(),now.getSeconds()].map(n=>String(n).padStart(2,'0')).join(':');
        lastEvW[w.id] = {tipo, detalle, ts};
        renderLastEv(w.id);
      }
    }
    prevW[w.id] = {estado:w.estado, cals:w.cals, promedio:w.promedio};
  });
}

function renderLastEv(id) {
  const card = wEls[id];
  if (!card) return;
  const ev  = lastEvW[id];
  const el  = card.querySelector('[data-lastev]');
  if (!el) return;
  if (!ev) { el.innerHTML = ''; return; }
  const label = ev.tipo==='c'?'CONTRATAR':ev.tipo==='k'?'CALIFICAR':'FINALIZAR';
  el.innerHTML =
    '<div class="lev-row1">' +
      '<span class="lev-chip ' + ev.tipo + '">' + label + '</span>' +
      '<span class="lev-ts">' + ev.ts + '</span>' +
    '</div>' +
    '<div class="lev-det">' + ev.detalle + '</div>';
}

function addEv(tipo, nombre, id, detalle) {
  const now = new Date();
  const ts  = [now.getHours(),now.getMinutes(),now.getSeconds()].map(n=>String(n).padStart(2,'0')).join(':');
  eventos.unshift({tipo,nombre,id,detalle,ts});
  if (eventos.length > 60) eventos.pop();
  const feed = document.getElementById('events-feed');
  const tipoLabel = tipo==='c'?'CONTRATAR':tipo==='k'?'CALIFICAR':tipo==='e'?'OCUPADO':'FINALIZAR';
  const div = document.createElement('div');
  div.className = 'ev ev-' + tipo;
  div.innerHTML =
    '<span class="ev-ts">'   + ts         + '</span>' +
    '<span class="ev-tipo ' + tipo + '">' + tipoLabel + '</span>' +
    '<span class="ev-det">'  + nombre + ' <span style="opacity:.5;font-family:monospace">(' + id + ')</span> — ' + detalle + '</span>';
  if (feed.firstChild && feed.firstChild.tagName === 'DIV' && feed.firstChild.style && !feed.firstChild.className) {
    feed.innerHTML = '';
  }
  feed.insertBefore(div, feed.firstChild);
  // Limitar a 60 entradas en el DOM
  while (feed.children.length > 60) feed.removeChild(feed.lastChild);
}

// ── Stress ───────────────────────────────────────────────────────────────
function toggleStress() {
  fetch(stressActivo ? '/api/stress/stop' : '/api/stress/start', {method:'POST'}).catch(()=>{});
}
function syncStressUI() {
  const btn = document.getElementById('stress-btn');
  btn.textContent = stressActivo ? '⏹ Detener simulación' : '▶ Iniciar simulación';
  btn.className   = 'stress-toggle ' + (stressActivo ? 'stop' : 'start');
}

// ── Raw cmd ──────────────────────────────────────────────────────────────
function enviarCmd(cmd) {
  if (!cmd) return;
  mostrarResp('Enviando: ' + cmd + '…', false);
  fetch('/api/cmd', {method:'POST', body:cmd})
    .then(r => r.json())
    .then(d => mostrarResp(d.resp || '(sin respuesta)', (d.resp||'').startsWith('ERROR')))
    .catch(e => mostrarResp('Error: ' + e.message, true));
}

// pendingCalif: workers finalizados que aún no fueron calificados → {contratoId}
let pendingCalif = {};

function actualizarSelectorWorkers(workers) {
  function repoblar(id, lista, empty) {
    const sel = document.getElementById(id);
    const prev = sel.value;
    while (sel.options.length > 1) sel.remove(1);
    lista.forEach(([val, label]) => {
      const o = document.createElement('option'); o.value = val; o.textContent = label; sel.appendChild(o);
    });
    if (prev) sel.value = prev;
  }
  const disp    = workers.filter(w => w.estado === 'Disponible');
  const trabajo = workers.filter(w => w.estado !== 'Disponible');

  repoblar('sel-obtener', workers.map(w => [w.id, w.nombre + ' (' + w.id + ') — ' + w.estado]), '');
  repoblar('c-worker',    workers.map(w => [w.id, w.nombre + ' (' + w.id + ') — ' + w.estado]), '');
  repoblar('f-worker',    trabajo.map(w => [w.id, w.nombre + ' (' + w.id + ') · ' + (w.contratoId||'')]), '');

  // CALIFICAR: workers con contrato pendiente de calificación (tracked en pendingCalif)
  const calOpts = Object.entries(pendingCalif).map(([id, d]) => [id, d.nombre + ' (' + id + ')']);
  repoblar('k-worker', calOpts, '');

  // Actualizar pendingCalif: si el worker volvió a ser contratado, ya no aplica
  trabajo.forEach(w => { delete pendingCalif[w.id]; });
}

function ejecutarRegistrar() {
  const id     = document.getElementById('r-id').value.trim();
  const nombre = document.getElementById('r-nombre').value.trim();
  const oficio = document.getElementById('r-oficio').value;
  const desc   = document.getElementById('r-desc').value.trim() || 'Sin descripción';
  if (!id || !nombre) { mostrarResp('ERROR: completá id y nombre', true); return; }
  enviarCmd('REGISTRAR ' + id + ' ' + nombre + ' ' + oficio + ' "' + desc + '"');
}

function ejecutarContratar() {
  const wid  = document.getElementById('c-worker').value;
  const cons = document.getElementById('c-consumer').value.trim() || 'cliente';
  const dur  = parseInt(document.getElementById('c-dur').value);
  if (!wid) { mostrarResp('ERROR: seleccioná un worker', true); return; }
  const d = new Date(Date.now() + dur * 60000);
  const p = n => String(n).padStart(2,'0');
  const fin = d.getFullYear()+'-'+p(d.getMonth()+1)+'-'+p(d.getDate())+'T'+p(d.getHours())+':'+p(d.getMinutes())+':'+p(d.getSeconds());
  const cid = cons.replace(/\s+/g, '-').toLowerCase() + '-' + Date.now().toString().slice(-4);
  enviarCmd('CONTRATAR ' + wid + ' ' + cid + ' "Trabajo manual" "' + fin + '"');
}

function ejecutarFinalizar() {
  const sel = document.getElementById('f-worker');
  const wid = sel.value;
  if (!wid) { mostrarResp('ERROR: seleccioná un worker en trabajo', true); return; }
  const opt = sel.options[sel.selectedIndex].value;
  // contratoId está en el texto de la opción después de ·, pero mejor buscarlo en los datos
  const cid = sel.options[sel.selectedIndex].text.split('·')[1]?.trim();
  if (!cid) { mostrarResp('ERROR: no se pudo obtener el id de contrato', true); return; }
  // guardar para CALIFICAR
  pendingCalif[wid] = {nombre: sel.options[sel.selectedIndex].text.split('(')[0].trim(), contratoId: cid};
  enviarCmd('FINALIZAR ' + wid + ' ' + cid);
}

function ejecutarCalificar() {
  const wid     = document.getElementById('k-worker').value;
  const stars   = document.getElementById('k-stars').value;
  const comment = document.getElementById('k-comment').value.trim() || 'Sin comentario';
  if (!wid || !pendingCalif[wid]) { mostrarResp('ERROR: seleccioná un worker finalizado', true); return; }
  const cid = pendingCalif[wid].contratoId;
  enviarCmd('CALIFICAR ' + wid + ' ' + cid + ' ' + stars + ' "' + comment + '"');
  delete pendingCalif[wid];
}

function mostrarResp(msg, err) {
  const box = document.getElementById('raw-resp');
  box.textContent = msg;
  box.className = 'raw-resp ' + (err ? 'err' : 'ok');
}
</script>
</body>
</html>
""";
}
