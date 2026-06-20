package com.steam.coordinacion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Membresia persistida e independiente por instancia de Proxy. */
public class RegistroMembresia {
    private static final Logger LOG = Logger.getLogger(RegistroMembresia.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ConcurrentHashMap<String, EstadoNodo> miembros = new ConcurrentHashMap<>();
    private final Path archivo;

    public RegistroMembresia(String path) {
        this.archivo = Path.of(path);
        cargar();
    }

    public void registrar(EstadoNodo nodo) {
        nodo.activo = true;
        nodo.ultimoHeartbeat = System.currentTimeMillis();
        miembros.put(nodo.id, nodo);
        persistir();
    }

    public void heartbeat(String id, long lamport) {
        miembros.computeIfPresent(id, (k, n) -> {
            n.activo = true;
            n.ultimoHeartbeat = System.currentTimeMillis();
            n.lamportJoin = Math.max(n.lamportJoin, lamport);
            return n;
        });
        persistir();
    }

    public void marcarCaido(String id) {
        miembros.computeIfPresent(id, (k, n) -> { n.activo = false; return n; });
        persistir();
    }

    public List<EstadoNodo> getActivos() {
        return getTodos().stream().filter(n -> n.activo).toList();
    }

    public List<EstadoNodo> getTodos() {
        return miembros.values().stream()
                .sorted(Comparator.comparing(n -> n.id))
                .toList();
    }

    private void cargar() {
        if (!Files.exists(archivo)) return;
        try {
            EstadoNodo[] nodos = GSON.fromJson(Files.readString(archivo, StandardCharsets.UTF_8),
                    EstadoNodo[].class);
            if (nodos != null) for (EstadoNodo n : nodos) {
                // El estado persistido es una pista; se exige health/registro para reactivarlo.
                n.activo = false;
                miembros.put(n.id, n);
            }
        } catch (Exception e) {
            LOG.warning("[MEMBRESIA] No se pudo reconstruir " + archivo + ": " + e.getMessage());
        }
    }

    private synchronized void persistir() {
        try {
            if (archivo.getParent() != null) Files.createDirectories(archivo.getParent());
            String json = GSON.toJson(new ArrayList<>(getTodos()));
            Path tmp = Path.of(archivo + "." + ProcessHandle.current().pid() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, archivo, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, archivo, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warning("[MEMBRESIA] Error persistiendo " + archivo + ": " + e.getMessage());
        }
    }
}
