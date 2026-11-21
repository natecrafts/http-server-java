import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;

@Command(name = "http-server", mixinStandardHelpOptions = true)
public class Main implements Runnable {
    @Option(names = "--directory", description = "Directory to serve files from")
    private Path directory;

    @Override
    public void run() {
        HttpHandler codeCraftersHandler = new CodeCraftersHandler(directory);
        try (HttpServer server = new HttpServer(codeCraftersHandler, 4221)) {
            server.run();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
