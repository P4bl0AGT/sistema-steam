package com.steam.common;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLParameters;
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
        try {
            socket.connect(new InetSocketAddress(host, puerto), Configuracion.connectTimeoutMs());
            socket.setSoTimeout(Configuracion.readTimeoutMs());
            if (socket instanceof SSLSocket ssl) {
                ssl.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(parameters);
                ssl.startHandshake();
            }
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        return socket;
    }

    public static ServerSocket servidor(int puerto) throws IOException {
        Configuracion.configurarTls();
        ServerSocketFactory factory = Configuracion.tlsEnabled()
                ? SSLServerSocketFactory.getDefault() : ServerSocketFactory.getDefault();
        ServerSocket server = factory.createServerSocket();
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(Configuracion.bindHost(), puerto));
            if (server instanceof SSLServerSocket ssl) {
                ssl.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                ssl.setNeedClientAuth(Configuracion.tlsClientAuth());
            }
        } catch (IOException e) {
            server.close();
            throw e;
        }
        return server;
    }

    /** Acepta una conexion y limita cuanto puede bloquear una lectura entrante. */
    public static Socket aceptar(ServerSocket server) throws IOException {
        Socket socket = server.accept();
        socket.setSoTimeout(Configuracion.readTimeoutMs());
        return socket;
    }
}

