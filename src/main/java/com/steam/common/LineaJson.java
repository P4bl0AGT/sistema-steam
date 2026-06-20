package com.steam.common;

import java.io.BufferedReader;
import java.io.IOException;

/** Lee una trama terminada en LF sin permitir payloads ilimitados. */
public final class LineaJson {
    private LineaJson() {}

    public static String leer(BufferedReader reader) throws IOException {
        return leer(reader, Configuracion.maxMessageBytes());
    }

    public static String leer(BufferedReader reader, int max) throws IOException {
        StringBuilder sb = new StringBuilder(Math.min(max, 4096));
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') {
                if (sb.length() >= max) throw new MensajeDemasiadoGrandeException(max);
                sb.append((char) c);
            }
        }
        if (c == -1 && sb.isEmpty()) return null;
        return sb.toString();
    }

    public static final class MensajeDemasiadoGrandeException extends IOException {
        public MensajeDemasiadoGrandeException(int max) { super("Mensaje excede " + max + " bytes/caracteres"); }
    }
}
