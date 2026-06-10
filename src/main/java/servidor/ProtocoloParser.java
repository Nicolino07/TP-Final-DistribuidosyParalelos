package servidor;

public class ProtocoloParser {

    // -------------------------------------------------------
    // Parsea una línea de texto del protocolo TCP y devuelve
    // un Comando con el tipo y los argumentos separados.
    //
    // Formato: TIPO arg1 arg2 "arg con espacios"
    // Ejemplos:
    //   REGISTRAR j01 Juan ALBANIL "Albañil con 10 años de experiencia"
    //   CONTRATAR j01 c01 "Reparar pared" "2024-12-31T18:00"
    //   CALIFICAR j01 c01 5 "Excelente trabajo"
    //   FINALIZAR j01 c01
    //   BUSCAR ALBANIL
    //   LISTAR
    //   STATS
    //   PING
    //   GUARDAR
    // -------------------------------------------------------

    public static Comando parsear(String linea) {
        if (linea == null || linea.isBlank()) {
            return new Comando(TipoComando.DESCONOCIDO, new String[0]);
        }

        String[] partes = dividir(linea.trim());
        if (partes.length == 0) {
            return new Comando(TipoComando.DESCONOCIDO, new String[0]);
        }

        TipoComando tipo;
        try {
            tipo = TipoComando.valueOf(partes[0].toUpperCase());
        } catch (IllegalArgumentException e) {
            tipo = TipoComando.DESCONOCIDO;
        }

        String[] args = new String[partes.length - 1];
        System.arraycopy(partes, 1, args, 0, args.length);

        return new Comando(tipo, args);
    }

    // -------------------------------------------------------
    // Divide respetando argumentos entre comillas dobles
    // -------------------------------------------------------
    private static String[] dividir(String linea) {
        java.util.List<String> partes = new java.util.ArrayList<>();
        StringBuilder actual = new StringBuilder();
        boolean enComillas = false;

        for (char c : linea.toCharArray()) {
            if (c == '"') {
                enComillas = !enComillas;
            } else if (c == ' ' && !enComillas) {
                if (!actual.isEmpty()) {
                    partes.add(actual.toString());
                    actual.setLength(0);
                }
            } else {
                actual.append(c);
            }
        }
        if (!actual.isEmpty()) {
            partes.add(actual.toString());
        }
        return partes.toArray(new String[0]);
    }

    // -------------------------------------------------------
    // Tipos de comando del protocolo
    // -------------------------------------------------------
    public enum TipoComando {
        REGISTRAR,   // REGISTRAR <id> <nombre> <oficio> "<descripcion>"
        CONTRATAR,   // CONTRATAR <idTrabajador> <idConsumidor> "<descripcion>" "<finEstimado>"
        FINALIZAR,   // FINALIZAR <idTrabajador> <idContrato>
        CALIFICAR,   // CALIFICAR <idTrabajador> <idContrato> <puntaje> "<comentario>"
        BUSCAR,      // BUSCAR <oficio>
        LISTAR,      // LISTAR
        OBTENER,     // OBTENER <idTrabajador>
        STATS,       // STATS
        PING,        // PING
        GUARDAR,     // GUARDAR
        DESCONOCIDO
    }

    // -------------------------------------------------------
    // Comando parseado listo para ejecutar
    // -------------------------------------------------------
    public static class Comando {
        private final TipoComando tipo;
        private final String[] args;

        public Comando(TipoComando tipo, String[] args) {
            this.tipo = tipo;
            this.args = args;
        }

        public TipoComando getTipo() { return tipo; }
        public String[] getArgs() { return args; }

        public String getArg(int i) {
            if (i >= args.length) throw new IllegalArgumentException("Argumento " + i + " no existe");
            return args[i];
        }

        public boolean tieneArgs(int cantidad) {
            return args.length >= cantidad;
        }
    }
}
