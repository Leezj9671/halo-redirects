package run.halo.redirects;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Halo only creates the beans listed in META-INF/plugin-components.idx, so a component that is
 * missing there silently never exists at runtime (and the plugin fails to start when another
 * bean needs it).
 */
class PluginComponentsIndexTest {
    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path INDEX = Path.of("src/main/resources/META-INF/plugin-components.idx");

    @Test
    void everyComponentIsListedInTheIndex() throws IOException {
        Set<String> components;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            components = files.filter(path -> path.toString().endsWith(".java"))
                .filter(PluginComponentsIndexTest::isComponent)
                .map(path -> SOURCES.relativize(path).toString()
                    .replace(".java", "").replace('/', '.').replace('\\', '.'))
                .collect(Collectors.toSet());
        }
        var indexed = new HashSet<>(Files.readAllLines(INDEX, StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .toList());

        assertEquals(components, indexed);
    }

    private static boolean isComponent(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains("\n@Component");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
