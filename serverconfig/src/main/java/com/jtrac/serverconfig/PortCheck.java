package com.jtrac.serverconfig;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Optional;

/** Checks whether a TCP port can be bound before it is written to the configuration. */
public final class PortCheck {

    private PortCheck() {
    }

    /** Empty when the port is free on that address; otherwise why it can't be used. */
    public static Optional<String> problem(String bindAddress, int port) {
        if (port < 1 || port > 65535) {
            return Optional.of("Use a port between 1 and 65535.");
        }
        try (ServerSocket socket = new ServerSocket()) {
            // Behave like the server will (the JVM's and Tomcat's defaults): on Linux and macOS
            // SO_REUSEADDR lets a listener bind while old connections sit in TIME_WAIT after a
            // restart, but still not while another program listens. On Windows the same option
            // would allow taking over a port that is in use, so it stays off there.
            socket.setReuseAddress(!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"));
            socket.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port));
            return Optional.empty();
        } catch (java.net.BindException e) {
            return Optional.of("Port " + port + " on " + bindAddress + " is already in use by another program, or needs "
                    + "administrator rights (ports below 1024 on Linux and macOS). Choose another port.");
        } catch (IOException e) {
            return Optional.of("Port " + port + " on " + bindAddress + " can't be used: " + e.getMessage());
        }
    }

    /** The first free port at or above {@code start}. */
    public static int firstFree(String bindAddress, int start) {
        for (int port = start; port < Math.min(65535, start + 200); port++) {
            if (problem(bindAddress, port).isEmpty()) {
                return port;
            }
        }
        return start;
    }
}
