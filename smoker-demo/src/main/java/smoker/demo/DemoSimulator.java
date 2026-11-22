package smoker.demo;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small hardware simulator for the suite checks (WP-05, R-37). It listens on {@code 127.0.0.1:<port>} and answers
 * one line for each line: {@code PING} gives {@code PONG}, {@code INC} gives the counter after +1, {@code GET} gives
 * the counter, {@code RESET} sets the counter to 0 and gives {@code OK}, {@code QUIT} gives {@code OK} and ends the process.
 *
 * <p>Helper modes of the same class, so that a suite needs no shell:
 * {@code --send <port> <command>} sends one line and prints the answer (exit code 1 when it cannot connect),
 * {@code --touch <file>} creates the file, {@code --exit <code>} ends with this exit code.
 */
public final class DemoSimulator {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private DemoSimulator() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "--send".equals(args[0])) {
            System.exit(send(Integer.parseInt(args[1]), args[2]));
        } else if (args.length == 2 && "--touch".equals(args[0])) {
            if (!new File(args[1]).createNewFile() && !new File(args[1]).exists()) System.exit(1);
            return;
        } else if (args.length == 2 && "--exit".equals(args[0])) {
            System.exit(Integer.parseInt(args[1]));
        } else if (args.length == 1) {
            serve(Integer.parseInt(args[0]));
        } else {
            System.err.println("usage: DemoSimulator <port> | --send <port> <command> | --touch <file> | --exit <code>");
            System.exit(2);
        }
    }

    /** Sends one line to the simulator and prints the answer. Used by the demo application and as a reset command. */
    public static String ask(int port, String command) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2000);
            s.setSoTimeout(5000);
            PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(s.getOutputStream(), StandardCharsets.US_ASCII), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            out.println(command);
            String answer = in.readLine();
            if (answer == null) throw new IOException("no answer");
            return answer;
        } finally {
            s.close();
        }
    }

    private static int send(int port, String command) {
        try {
            System.out.println(ask(port, command));
            return 0;
        } catch (IOException e) {
            System.err.println("simulator: " + e);
            return 1;
        }
    }

    private static void serve(int port) throws IOException {
        final ServerSocket server = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
        System.out.println("simulator listening on 127.0.0.1:" + port);
        while (true) {
            final Socket s = server.accept();
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    handle(s);
                }
            }, "sim-connection");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void handle(Socket s) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(s.getOutputStream(), StandardCharsets.US_ASCII), true);
            String line;
            while ((line = in.readLine()) != null) {
                String cmd = line.trim();
                if ("PING".equals(cmd)) out.println("PONG");
                else if ("INC".equals(cmd)) out.println(COUNTER.incrementAndGet());
                else if ("GET".equals(cmd)) out.println(COUNTER.get());
                else if ("RESET".equals(cmd)) {
                    COUNTER.set(0);
                    out.println("OK");
                } else if ("QUIT".equals(cmd)) {
                    out.println("OK");
                    out.flush();
                    Runtime.getRuntime().halt(0);
                } else out.println("ERR unknown command");
            }
        } catch (IOException e) {
            // the client went away
        } finally {
            try {
                s.close();
            } catch (IOException e) {
                // ignored
            }
        }
    }
}
