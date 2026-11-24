import codecrafters.CodeCraftersRequestHandler;

import http.HttpConnectionHandler;
import http.HttpRequestHandler;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import tcp.TcpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

@Command(name = "http-server", mixinStandardHelpOptions = true)
public class Main implements Runnable {
    @Option(names = "--directory", description = "Directory to serve files from")
    private Path directory;

    @Override
    public void run() {
        HttpRequestHandler codeCraftersRequestHandler = new CodeCraftersRequestHandler(directory);
        HttpConnectionHandler codeCraftersConnectionHandler =
                new HttpConnectionHandler(codeCraftersRequestHandler);
        try (TcpServer server = new TcpServer(codeCraftersConnectionHandler, 4221)) {
            server.run();
        } catch (IOException e) {
            throw new UncheckedIOException("I/O error while running TCP server.", e);
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
