package com.steam.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicBoolean;

/** Configuracion externa comun a todos los procesos. */
public final class Configuracion {
    private static final Logger LOG = Logger.getLogger(Configuracion.class.getName());
    private static final Properties PROPS = cargar();
    private static final AtomicBoolean TLS_CONFIGURADO = new AtomicBoolean();

    private Configuracion() {}

    private static Properties cargar() {
        Properties p = new Properties();
        String archivo = System.getProperty("steam.config", System.getenv().getOrDefault(
                "STEAM_CONFIG", "config/steam.properties"));
        Path path = Path.of(archivo);
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo leer configuracion: " + path, e);
            }
        }
        return p;
    }

    public static String get(String key, String defecto) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) return sys.trim();
        String envKey = key.toUpperCase(Locale.ROOT).replace('.', '_');
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) return env.trim();
        return PROPS.getProperty(key, defecto).trim();
    }

    public static int getInt(String key, int defecto) {
        try { return Integer.parseInt(get(key, String.valueOf(defecto))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Entero invalido para " + key, e); }
    }

    public static long getLong(String key, long defecto) {
        try { return Long.parseLong(get(key, String.valueOf(defecto))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Long invalido para " + key, e); }
    }

    public static boolean getBoolean(String key, boolean defecto) {
        return Boolean.parseBoolean(get(key, String.valueOf(defecto)));
    }

    public static List<Endpoint> proxies() {
        String raw = get("steam.proxies", "localhost:8080,localhost:8085");
        List<Endpoint> endpoints = new ArrayList<>();
        for (String item : raw.split(",")) if (!item.isBlank()) endpoints.add(Endpoint.parse(item));
        if (endpoints.isEmpty()) throw new IllegalStateException("steam.proxies no contiene destinos");
        return List.copyOf(endpoints);
    }

    public static String hostServicio(String servicio, int nodo) {
        return get("steam." + servicio.toLowerCase(Locale.ROOT) + "." + nodo + ".host",
                get("steam.advertised.host", "localhost"));
    }

    public static String advertisedHost() { return get("steam.advertised.host", "localhost"); }
    public static String bindHost()       { return get("steam.bind.host", "127.0.0.1"); }
    public static int connectTimeoutMs()  { return getInt("steam.connect.timeout.ms", 3_000); }
    public static int readTimeoutMs()     { return getInt("steam.read.timeout.ms", Constantes.TIMEOUT_MS); }
    public static int maxMessageBytes()   { return getInt("steam.max.message.bytes", 65_536); }
    public static int maxReplicationBytes(){ return getInt("steam.max.replication.bytes", 16_777_216); }
    public static long requestMaxAgeMs()  { return getLong("steam.request.max.age.ms", 60_000L); }
    public static long requestCacheTtlMs(){ return getLong("steam.request.cache.ttl.ms", 120_000L); }
    public static long tokenTtlMs()       { return getLong("steam.token.ttl.ms", 1_800_000L); }
    public static long mutexLeaseMs()     { return getLong("steam.mutex.lease.ms", 15_000L); }
    public static long replicationIntervalMs(){ return getLong("steam.replication.interval.ms", 5_000L); }
    public static int writerNodeId(String servicio) {
        return getInt("steam." + servicio.toLowerCase(Locale.ROOT) + ".writer.node", 1);
    }
    public static boolean demoMode()      { return getBoolean("steam.demo.mode", false); }
    public static String controlSecret()  {
        String system = System.getProperty("steam.control.secret");
        if (system != null && !system.isBlank()) return system.trim();
        String environment = System.getenv("STEAM_CONTROL_SECRET");
        if (environment != null && !environment.isBlank()) return environment.trim();
        if (!demoMode()) {
            throw new IllegalStateException(
                    "STEAM_CONTROL_SECRET es obligatorio cuando steam.demo.mode=false");
        }
        String demo = PROPS.getProperty("steam.control.secret", "").trim();
        if (demo.isBlank()) throw new IllegalStateException("Falta steam.control.secret para el perfil demo");
        return demo;
    }
    public static boolean tlsEnabled()    { return getBoolean("steam.tls.enabled", false); }
    public static boolean tlsClientAuth() { return getBoolean("steam.tls.client.auth", false); }

    /** Valida secretos y TLS antes de que un proceso abra sus puertos. */
    public static void validarArranque() {
        String secret = controlSecret();
        if (!demoMode() && secret.length() < 32) {
            throw new IllegalStateException("STEAM_CONTROL_SECRET debe tener al menos 32 caracteres");
        }
        String bind = bindHost();
        if (demoMode() && !("127.0.0.1".equals(bind) || "localhost".equalsIgnoreCase(bind)
                || "::1".equals(bind))) {
            throw new IllegalStateException(
                    "El modo demo solo puede escuchar en loopback; use steam.demo.mode=false");
        }
        configurarTls();
    }

    public static synchronized void configurarTls() {
        if (!tlsEnabled()) return;
        if (!TLS_CONFIGURADO.compareAndSet(false, true)) return;
        asignarPropiedadTls("javax.net.ssl.keyStore", "steam.tls.keystore");
        asignarPropiedadTls("javax.net.ssl.keyStorePassword", "steam.tls.keystore.password");
        asignarPropiedadTls("javax.net.ssl.trustStore", "steam.tls.truststore");
        asignarPropiedadTls("javax.net.ssl.trustStorePassword", "steam.tls.truststore.password");
        LOG.info("[TLS] Perfil TLS habilitado. clientAuth=" + tlsClientAuth());
    }

    private static void asignarPropiedadTls(String javaKey, String configKey) {
        String value = get(configKey, "");
        if (value.isBlank()) throw new IllegalStateException("Falta " + configKey + " para TLS");
        System.setProperty(javaKey, value);
    }
}
