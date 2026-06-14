package servidor;

import servidor.modelo.*;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Ventana Swing del servidor — vista de administración local.
 *
 * <p>Muestra en tiempo real la tabla de trabajadores (refrescada cada 2 segundos
 * desde el EDT mediante un {@link javax.swing.Timer}) y un área de log donde
 * {@link ServidorTCP} y {@link ManejadorCliente} reportan eventos de red.
 *
 * <p>Es la única clase que toca componentes Swing; todas las actualizaciones
 * se redirigen al Event Dispatch Thread mediante {@code SwingUtilities.invokeLater()}.
 * Los workers con prefijo {@code bench} se filtran de la tabla para mostrar
 * solo los trabajadores reales del grafo persistido.
 */
public class VentanaPrincipal extends JFrame {

    private final GrafoTrabajadores grafo;
    private final DefaultTableModel modeloTabla;
    private final JTextArea areaLog;
    private final JLabel lblStats;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public VentanaPrincipal(GrafoTrabajadores grafo, int puerto) {
        this.grafo = grafo;

        setTitle("Plataforma de Oficios — Servidor TCP :" + puerto);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(950, 620);
        setLocationRelativeTo(null);

        // ----- Tabla de trabajadores -----
        String[] columnas = {"ID", "Nombre", "Oficio", "Estado", "Promedio", "Calificaciones"};
        modeloTabla = new DefaultTableModel(columnas, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tabla = new JTable(modeloTabla);
        tabla.setFillsViewportHeight(true);
        tabla.getTableHeader().setReorderingAllowed(false);
        tabla.setRowHeight(22);

        // ----- Área de log -----
        areaLog = new JTextArea();
        areaLog.setEditable(false);
        areaLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollLog = new JScrollPane(areaLog);

        JPanel panelLog = new JPanel(new BorderLayout());
        panelLog.setBorder(BorderFactory.createTitledBorder("Log del servidor"));
        panelLog.add(scrollLog, BorderLayout.CENTER);
        panelLog.setPreferredSize(new Dimension(0, 180));

        // ----- Panel norte: stats + botón -----
        lblStats = new JLabel("Iniciando...");
        JButton btnRefrescar = new JButton("Refrescar");
        btnRefrescar.addActionListener(e -> refrescarTabla());

        JPanel panelNorte = new JPanel(new BorderLayout(8, 0));
        panelNorte.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
        panelNorte.add(lblStats, BorderLayout.CENTER);
        panelNorte.add(btnRefrescar, BorderLayout.EAST);

        // ----- Layout principal -----
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(tabla), panelLog);
        split.setResizeWeight(0.65);

        add(panelNorte, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        // Auto-refresh cada 2 segundos
        Timer timer = new Timer(2000, e -> refrescarTabla());
        timer.start();

        refrescarTabla();
    }

    // Llamado desde cualquier hilo — redirige a EDT
    public void log(String mensaje) {
        String ts = LocalDateTime.now().format(FMT);
        SwingUtilities.invokeLater(() -> {
            areaLog.append("[" + ts + "] " + mensaje + "\n");
            areaLog.setCaretPosition(areaLog.getDocument().getLength());
        });
    }

    private void refrescarTabla() {
        List<NodoTrabajador> lista = grafo.listarTodos();
        modeloTabla.setRowCount(0);
        for (NodoTrabajador nodo : lista) {
            if (nodo.getTrabajador().getId().startsWith("bench")) continue;
            Trabajador t = nodo.getTrabajador();
            modeloTabla.addRow(new Object[]{
                t.getId(),
                t.getNombre(),
                t.getOficio().getEtiqueta(),
                nodo.getEstado().getEtiqueta(),
                String.format("%.1f", nodo.getPromedio()),
                nodo.getCalificaciones().size()
            });
        }
        lblStats.setText("Trabajadores: " + grafo.cantidadTrabajadores()
                + "  |  En trabajo: " + grafo.obtenerNodosEnTrabajo().size());
    }
}