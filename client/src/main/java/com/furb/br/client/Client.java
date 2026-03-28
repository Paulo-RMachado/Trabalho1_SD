/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */

package com.furb.br.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 * @author prmachado
 */
public class Client {

    public static void main(String[] args) {
        Config config = parseArgs(args);
        long startRealMs = System.currentTimeMillis();
        AtomicLong clockOffsetMs = new AtomicLong(0L);

        Thread listenerThread = new Thread(() -> runListener(config, startRealMs, clockOffsetMs), "client-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();

        System.out.println("Client port " + config.port + ", initial clock(ms)=" + config.initialClockMs
                + ", interval(ms)=" + config.intervalMs);

        while (true) {
            try {
                if (isMaster(config)) {
                    syncRound(config, startRealMs, clockOffsetMs);
                }
                long now = currentTimeMs(config, startRealMs, clockOffsetMs);
                System.out.println("Clock(ms)=" + now + ", offset(ms)=" + clockOffsetMs.get());
                Thread.sleep(config.intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static void runListener(Config config, long startRealMs, AtomicLong clockOffsetMs) {
        System.out.println("Listener active on port " + config.port);
        try (ServerSocket serverSocket = new ServerSocket(config.port)) {
            while (true) {
                try (Socket socket = serverSocket.accept()) {
                    handleConnection(socket, config, startRealMs, clockOffsetMs);
                } catch (IOException e) {
                    System.out.println("Connection error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.out.println("Failed to start client listener: " + e.getMessage());
        }
    }

    private static void handleConnection(Socket socket, Config config, long startRealMs, AtomicLong clockOffsetMs) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);

        String line = reader.readLine();
        if (line == null) {
            return;
        }

        String[] parts = line.trim().split("\\s+");
        if (parts.length == 2 && "TIME".equalsIgnoreCase(parts[0])) {
            long serverTime = parseLong(parts[1]);
            long clientNow = currentTimeMs(config, startRealMs, clockOffsetMs);
            long diff = clientNow - serverTime;
            writer.println("DIFF " + diff);

            String adjustLine = reader.readLine();
            if (adjustLine != null) {
                String[] adjustParts = adjustLine.trim().split("\\s+");
                if (adjustParts.length == 2 && "ADJUST".equalsIgnoreCase(adjustParts[0])) {
                    long delta = parseLong(adjustParts[1]);
                    clockOffsetMs.addAndGet(delta);
                    System.out.println("Applied adjust(ms)=" + delta + ", clock offset(ms)=" + clockOffsetMs.get());
                }
            }
        }
    }

    private static long currentTimeMs(Config config, long startRealMs, AtomicLong clockOffsetMs) {
        long elapsed = System.currentTimeMillis() - startRealMs;
        return config.initialClockMs + elapsed + clockOffsetMs.get();
    }

    private static boolean isMaster(Config config) {
        int elected = config.port;
        for (Peer peer : config.peers) {
            if (peer.port < elected && isPeerActive(peer, config)) {
                elected = peer.port;
            }
        }
        return elected == config.port;
    }

    private static boolean isPeerActive(Peer peer, Config config) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(peer.host, peer.port), config.connectTimeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void syncRound(Config config, long startRealMs, AtomicLong clockOffsetMs) {
        long masterNow = currentTimeMs(config, startRealMs, clockOffsetMs);
        List<ClientSession> sessions = new ArrayList<>();

        for (Peer peer : config.peers) {
            if (isLocalPeer(config, peer)) {
                continue;
            }

            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(peer.host, peer.port), config.connectTimeoutMs);
                socket.setSoTimeout(config.ioTimeoutMs);

                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);

                writer.println("TIME " + masterNow);
                String diffLine = reader.readLine();
                if (diffLine != null) {
                    String[] parts = diffLine.trim().split("\\s+");
                    if (parts.length == 2 && "DIFF".equalsIgnoreCase(parts[0])) {
                        long diff = parseLong(parts[1]);
                        sessions.add(new ClientSession(socket, reader, writer, diff, peer));
                        continue;
                    }
                }

                socket.close();
            } catch (IOException e) {
                System.out.println("Failed to reach peer " + peer.host + ":" + peer.port + ": " + e.getMessage());
            }
        }

        long sumDiffs = 0L;
        for (ClientSession session : sessions) {
            sumDiffs += session.diffMs;
        }

        long avgDiff = sessions.isEmpty() ? 0L : (sumDiffs / (sessions.size() + 1));

        for (ClientSession session : sessions) {
            long adjust = avgDiff - session.diffMs;
            session.writer.println("ADJUST " + adjust);
            try {
                session.socket.close();
            } catch (IOException e) {
                System.out.println("Close error for peer " + session.peer.host + ":" + session.peer.port + ": " + e.getMessage());
            }
        }
    }

    private static boolean isLocalPeer(Config config, Peer peer) {
        if (peer.port != config.port) {
            return false;
        }
        return "localhost".equalsIgnoreCase(peer.host) || "127.0.0.1".equals(peer.host);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static Config parseArgs(String[] args) {
        Long initialClockMs = null;
        Integer port = null;
        Long intervalMs = null;
        String peersArg = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-d".equals(arg) && i + 1 < args.length) {
                initialClockMs = parseLong(args[++i]);
            } else if ("-p".equals(arg) && i + 1 < args.length) {
                port = (int) parseLong(args[++i]);
            } else if ("-i".equals(arg) && i + 1 < args.length) {
                intervalMs = parseLong(args[++i]);
            } else if ("-peers".equals(arg) && i + 1 < args.length) {
                peersArg = args[++i];
            } else if ("-h".equals(arg) || "--help".equals(arg)) {
                printUsageAndExit();
            }
        }

        if (port == null || initialClockMs == null) {
            printUsageAndExit();
        }

        if (intervalMs == null || intervalMs <= 0L) {
            intervalMs = 5000L;
        }

        List<Peer> peers = parsePeers(peersArg);
        if (peers.isEmpty()) {
            printUsageAndExit();
        }

        return new Config(port, initialClockMs, intervalMs, peers);
    }

    private static List<Peer> parsePeers(String peersArg) {
        List<Peer> peers = new ArrayList<>();
        if (peersArg == null || peersArg.trim().isEmpty()) {
            return peers;
        }

        String[] entries = peersArg.split(",");
        for (String entry : entries) {
            String[] parts = entry.trim().split(":");
            if (parts.length != 2) {
                continue;
            }

            String host = parts[0].trim();
            int port = (int) parseLong(parts[1].trim());
            if (!host.isEmpty() && port > 0) {
                peers.add(new Peer(host, port));
            }
        }

        return peers;
    }

    private static void printUsageAndExit() {
        System.out.println("Usage: java Client -p <port> -d <initialClockMs> -peers <host:port,host:port> -i <intervalMs>");
        System.exit(1);
    }

    private static class Config {
        final int port;
        final long initialClockMs;
        final long intervalMs;
        final List<Peer> peers;
        final int connectTimeoutMs;
        final int ioTimeoutMs;

        Config(int port, long initialClockMs, long intervalMs, List<Peer> peers) {
            this.port = port;
            this.initialClockMs = initialClockMs;
            this.intervalMs = intervalMs;
            this.peers = peers;
            this.connectTimeoutMs = 800;
            this.ioTimeoutMs = 1000;
        }
    }

    private static class Peer {
        final String host;
        final int port;

        Peer(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static class ClientSession {
        final Socket socket;
        final BufferedReader reader;
        final PrintWriter writer;
        final long diffMs;
        final Peer peer;

        ClientSession(Socket socket, BufferedReader reader, PrintWriter writer, long diffMs, Peer peer) {
            this.socket = socket;
            this.reader = reader;
            this.writer = writer;
            this.diffMs = diffMs;
            this.peer = peer;
        }
    }
}
