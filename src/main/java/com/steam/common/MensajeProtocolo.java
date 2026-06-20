package com.steam.common;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * POJO que actúa como sobre de comunicación entre todos los componentes.
 * Se serializa a JSON via Gson para el transporte TCP.
 *
 * Campos de seguridad:
 *  - requestId  → identifica unívocamente la solicitud (anti-replay)
 *  - token      → token de sesión emitido por svSesiones
 *  - timestamp  → sello temporal para detectar mensajes obsoletos
 */
public class MensajeProtocolo {

    // ── Tipos de mensaje ──────────────────────────────────────────────────────
    public static final String TIPO_REQUEST  = "REQUEST";
    public static final String TIPO_RESPONSE = "RESPONSE";

    // ── Campos del protocolo ──────────────────────────────────────────────────
    private String              requestId;
    private String              tipo;        // REQUEST | RESPONSE
    private String              operacion;
    private String              token;       // token de sesión (null en LOGIN)
    private String              rolUsuario;  // rol del emisor
    private Map<String, Object> payload;     // datos específicos de la operación
    private String              status;        // OK | ERROR
    private String              mensaje;       // descripción legible del resultado
    private long                timestamp;
    private long                lamportClock;  // reloj de Lamport del emisor
    private int                 versionProtocolo = 1;
    private String              codigoError;   // BUSINESS | UNAVAILABLE | SECURITY | ...
    private String              emisor;
    private String              receptor;

    // ── Constructores de fábrica ──────────────────────────────────────────────

    public MensajeProtocolo() {
        this.requestId = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
        this.payload   = new HashMap<>();
    }

    /** Crea un REQUEST con token de sesión. */
    public static MensajeProtocolo request(String operacion, String token) {
        MensajeProtocolo m = new MensajeProtocolo();
        m.tipo      = TIPO_REQUEST;
        m.operacion = operacion;
        m.token     = token;
        return m;
    }

    /** Crea un RESPONSE exitoso. */
    public static MensajeProtocolo ok(String requestId, String mensaje) {
        MensajeProtocolo m = new MensajeProtocolo();
        m.requestId = requestId;
        m.tipo      = TIPO_RESPONSE;
        m.status    = Constantes.OK;
        m.mensaje   = mensaje;
        return m;
    }

    /** Crea un RESPONSE de error. */
    public static MensajeProtocolo error(String requestId, String mensaje) {
        MensajeProtocolo m = new MensajeProtocolo();
        m.requestId = requestId;
        m.tipo      = TIPO_RESPONSE;
        m.status    = Constantes.ERROR;
        m.mensaje   = mensaje;
        return m;
    }

    // ── Serialización ─────────────────────────────────────────────────────────

    private static final Gson GSON = new Gson();

    public String toJson() {
        return GSON.toJson(this);
    }

    public static MensajeProtocolo fromJson(String json) {
        MensajeProtocolo m = GSON.fromJson(json, MensajeProtocolo.class);
        // Gson deserializa Map<String,Object> con Double para números; normalizar
        if (m != null && m.payload == null) {
            m.payload = new HashMap<>();
        }
        return m;
    }

    // ── Helpers de payload ────────────────────────────────────────────────────

    public MensajeProtocolo put(String key, Object value) {
        if (payload == null) payload = new HashMap<>();
        payload.put(key, value);
        return this;
    }

    public Object get(String key) {
        return payload != null ? payload.get(key) : null;
    }

    public String getString(String key) {
        Object v = get(key);
        return v != null ? v.toString() : null;
    }

    public double getDouble(String key) {
        Object v = get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s)  return Double.parseDouble(s);
        return 0.0;
    }

    public int getInt(String key) {
        Object v = get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s)  return Integer.parseInt(s);
        return 0;
    }

    public boolean isOk() {
        return Constantes.OK.equals(status);
    }

    public MensajeProtocolo setCodigoErrorFluent(String value) {
        this.codigoError = value;
        return this;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public String getRequestId()                   { return requestId; }
    public void   setRequestId(String v)           { this.requestId = v; }

    public String getTipo()                        { return tipo; }
    public void   setTipo(String v)                { this.tipo = v; }

    public String getOperacion()                   { return operacion; }
    public void   setOperacion(String v)           { this.operacion = v; }

    public String getToken()                       { return token; }
    public void   setToken(String v)               { this.token = v; }

    public String getRolUsuario()                  { return rolUsuario; }
    public void   setRolUsuario(String v)          { this.rolUsuario = v; }

    public Map<String, Object> getPayload()        { return payload; }
    public void setPayload(Map<String, Object> v)  { this.payload = v; }

    public String getStatus()                      { return status; }
    public void   setStatus(String v)              { this.status = v; }

    public String getMensaje()                     { return mensaje; }
    public void   setMensaje(String v)             { this.mensaje = v; }

    public long  getTimestamp()                    { return timestamp; }
    public void  setTimestamp(long v)              { this.timestamp = v; }

    public long  getLamportClock()                 { return lamportClock; }
    public void  setLamportClock(long v)           { this.lamportClock = v; }

    public int getVersionProtocolo()                { return versionProtocolo; }
    public void setVersionProtocolo(int v)          { this.versionProtocolo = v; }

    public String getCodigoError()                  { return codigoError; }
    public void setCodigoError(String v)            { this.codigoError = v; }

    public String getEmisor()                       { return emisor; }
    public void setEmisor(String v)                 { this.emisor = v; }

    public String getReceptor()                     { return receptor; }
    public void setReceptor(String v)               { this.receptor = v; }

    @Override
    public String toString() {
        return "[" + tipo + "|" + operacion + "|" + status + "|" + requestId + "]";
    }
}
