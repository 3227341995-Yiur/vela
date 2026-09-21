import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * A stand-in for {@code jar tf} / {@code jar cf}, because the JetBrains
 * Runtimes installed here ship {@code java} and {@code javac} but no
 * {@code jar} and no {@code javap}.
 *
 * Subcommands:
 *   create <out.jar> [--root <dir>]...   pack dirs (contents) into a jar
 *   extract <in.jar> <dir>               unpack (for load-order checks)
 *   tf <in.jar>                          list entries, one per line ("jar tf")
 */
public final class JarTool {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        switch (args[0]) {
            case "create" -> create(args);
            case "extract" -> extract(args);
            case "tf" -> list(args[1]);
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    private static void usage() {
        System.err.println("usage: JarTool create <out.jar> [--root <dir>]...");
        System.err.println("       JarTool extract <in.jar> <dir>");
        System.err.println("       JarTool tf <in.jar>");
    }

    private static void create(String[] args) throws IOException {
        Path out = Path.of(args[1]);
        List<Path> roots = new ArrayList<>();
        boolean manifest = true;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--no-manifest" -> manifest = false;
                case "--root" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--root needs a directory");
                    }
                    roots.add(Path.of(args[++i]));
                }
                default -> throw new IllegalArgumentException("expected --root <dir> or --no-manifest, got " + args[i]);
            }
        }
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("no --root <dir> given: nothing to pack");
        }

        Files.createDirectories(out.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out), StandardCharsets.UTF_8)) {
            zip.setLevel(9);
            if (manifest) {
                writeManifest(zip);
            }
            int count = 0;
            for (Path root : roots) {
                if (!Files.isDirectory(root)) {
                    throw new IllegalArgumentException("not a directory: " + root);
                }
                try (Stream<Path> walk = Files.walk(root)) {
                    List<Path> files = new ArrayList<>();
                    walk.filter(Files::isRegularFile).forEach(files::add);
                    files.sort(Comparator.comparing(p -> root.relativize(p).toString().replace('\\', '/')));
                    for (Path file : files) {
                        String name = root.relativize(file).toString().replace('\\', '/');
                        if (name.equals("META-INF/MANIFEST.MF")) {
                            continue; // ours wins
                        }
                        ZipEntry entry = new ZipEntry(name);
                        entry.setTime(0L); // reproducible: no timestamps leaking in
                        zip.putNextEntry(entry);
                        Files.copy(file, zip);
                        zip.closeEntry();
                        count++;
                    }
                }
            }
            System.out.println("[JarTool] " + out + " <- " + count + " entries from " + roots.size() + " root(s)");
        }
        System.out.println("[JarTool] size = " + Files.size(out) + " bytes");
    }

    private static void writeManifest(ZipOutputStream zip) throws IOException {
        String manifest = "Manifest-Version: 1.0\r\n"
                + "Implementation-Title: Vela IDEA plugin\r\n"
                + "Implementation-Version: 0.1.0\r\n"
                + "Created-By: JarTool (offline build, no Gradle)\r\n"
                + "\r\n";
        ZipEntry entry = new ZipEntry("META-INF/MANIFEST.MF");
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(manifest.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void extract(String[] args) throws IOException {
        Path jar = Path.of(args[1]);
        Path dest = Path.of(args[2]);
        try (ZipFile zip = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = dest.resolve(entry.getName());
                if (!target.normalize().startsWith(dest.normalize())) {
                    throw new IOException("entry escapes destination: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target);
                }
            }
        }
        System.out.println("[JarTool] extracted " + jar + " -> " + dest);
    }

    private static void list(String jar) throws IOException {
        try (ZipFile zip = new ZipFile(Path.of(jar).toFile(), StandardCharsets.UTF_8)) {
            List<String> names = new ArrayList<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                System.out.println(name);
            }
        }
    }

    private JarTool() {
    }
}
