package servidor.modelo;

public enum Oficio {
    ALBANIL("Albañil"),
    ELECTRICISTA("Electricista"),
    PLOMERO("Plomero"),
    PELUQUERO("Peluquero/a"),
    MAESTRO("Maestro/a"),
    CARPINTERO("Carpintero/a"),
    INGENIERO("Ingeniero/a"),
    PROFESOR("Profesor/a");

    private final String etiqueta;

    Oficio(String etiqueta) {
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
