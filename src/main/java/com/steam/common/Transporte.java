package com.steam.common;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** Fabrica unica de TCP plano o TLS configurable. */
public final class Transporte {
    private Transporte() {}

    public static Socket conectar(String host, int puerto) throws IOException {
        Configuracion.configurarTls();
        SocketFactory factory = Configuracion.tlsEnabled()
                ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
        Socket socket = factory.createSocket();
        socket.connect(new InetSocketAddress(host, puerto), Configuracion.connectTimeoutMs());
        socket.setSoTimeout(Configuracion.readTimeoutMs());
        if (socket instanceof SSLSocket ssl) {
            ssl.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            ssl.startHandshake();
        }
        return socket;
    }

    public static ServerSocket servidor(int puerto) throws IOException {
        Configuracion.configurarTls();
        ServerSocketFactory factory = Configuracion.tlsEnabled()
                ? SSLServerSocketFactory.getDefault() : ServerSocketFactory.getDefault();
        ServerSocket server = factory.createServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(Configuracion.bindHost(), puerto));
        if (server instanceof SSLServerSocket ssl) {
            ssl.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            ssl.setNeedClientAuth(Configuracion.tlsClientAuth());
        }
        return server;
    }
}

