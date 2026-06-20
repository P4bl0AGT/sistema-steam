package com.steam.common;

/** Host y puerto inmutables para un destino TCP. */
public record Endpoint(String host, int puerto) {
    public Endpoint {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Host vacio");
        if (puerto < 1 || puerto > 65_535) throw new IllegalArgumentException("Puerto invalido: " + puerto);
    }

    public static Endpoint parse(String value) {
        String[] partes = value.trim().split(":", 2);
        if (partes.length != 2) throw new IllegalArgumentException("Endpoint invalido: " + value);
        return new Endpoint(partes[0].trim(), Integer.parseInt(partes[1].trim()));
    }

    @Override public String toString() { return host + ":" + puerto; }
}

