package com.steam.coordinacion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * RegistroMembresia – lista de membresía del clúster de svJuegos.
 *
 * El Proxy actualiza este registro en cada ciclo de health check.
 * Se persiste en data/MEMBRESIA.txt para auditoría post-mortem.
 *
 * Es thread-safe: ConcurrentHashMap para lecturas concurrentes,
 * synchronized en persistir() para evitar escrituras entrelazadas.
 */
public class RegistroMembresia {

    private static final Logger LOG  = Logger.getLogger(RegistroMembresia.class.getName());
    private static final Gson   GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ARCHIVO = "data/MEMBRESIA.txt";

    private final ConcurrentHashMap<Integer, EstadoNodo> miembros =
            new ConcurrentHashMap<>();
    private volatile int coordinadorId = -1;

    // ── API pública ───────────────────────────────────────────────────────────

    /** Registra o actualiza un nodo como ACTIVO. */
    public void registrar(EstadoNodo nodo) {
        miembros.put(nodo.id, nodo);
        nodo.activo          = true;
        nodo.ultimoHeartbeat = System.currentTimeMillis();
        persistir();
    }

    /** Marca el nodo como caído sin eliminarlo del mapa. */
    public void marcarCaido(int id) {
        EstadoNodo n = miembros.get(id);
        if (n != null && n.activo) {
            n.activo = false;
            persistir();
        }
    }

    public List<EstadoNodo> getActivos() {
        return miembros.values().stream()
                .filter(n -> n.activo)
                .collect(Collectors.toList());
    }

    public List<EstadoNodo> getTodos() {
        return new ArrayList<>(miembros.values());
    }

    public int  getCoordinador()      { return coordinadorId; }
    public void setCoordinador(int id){ this.coordinadorId = id; }

    // ── Persistencia ──────────────────────────────────────────────────────────

    private synchronized void persistir() {
        try {
            Files.createDirectories(Path.of("data"));
            String json = GSON.toJson(getTodos());
            Path tmp  = Path.of(ARCHIVO + ".tmp");
            Path dest = Path.of(ARCHIVO);
            Files.writeString(tmp, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warning("[MEMBRESIA] Error persistiendo: " + e.getMessage());
        }
    }
}
