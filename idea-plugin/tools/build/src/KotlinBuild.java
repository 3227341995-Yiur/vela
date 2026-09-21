import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiny launcher for the Kotlin/JVM compiler bundled with a JetBrains IDE.
 *
 * It exists for one reason: the IDE lives under a path with spaces in it
 * ("./JetBrains/IntelliJ IDEA 2026.2.1"), the compile classpath is made of
 * several hundred jars, and neither a Windows command line (32k limit) nor the
 * Kotlin compiler's own "@argfile" syntax (which strips backslashes and splits
 * on whitespace) can carry that.  So the classpath travels in a file and this
 * program builds the compiler arguments in memory.
 *
 * Usage:
 *   java -cp "kotlin-compiler.jar;tools" KotlinBuild
 *        --classpath-file <file>     one classpath, path-separated, first line
 *        --kotlin-home <dir>         the kotlinc/ distribution directory
 *        --out <dir>                 output directory for .class files
 *        --jvm-target <n>            e.g. 21
 *        --no-stdlib / --no-reflect  passthrough switches
 *        -- <source.kt> ...          the Kotlin sources
 */
public final class KotlinBuild {

    public static void main(String[] args) throws Exception {
        List<String> compilerArgs = new ArrayList<>();
        List<String> sources = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--classpath-file":
                    compilerArgs.add("-classpath");
                    compilerArgs.add(readClasspath(args[++i]));
                    break;
                case "--kotlin-home":
                    compilerArgs.add("-kotlin-home");
                    compilerArgs.add(args[++i]);
                    break;
                case "--out":
                    compilerArgs.add("-d");
                    compilerArgs.add(args[++i]);
                    break;
                case "--jvm-target":
                    compilerArgs.add("-jvm-target");
                    compilerArgs.add(args[++i]);
                    break;
                case "--api-version":
                    compilerArgs.add("-api-version");
                    compilerArgs.add(args[++i]);
                    break;
                case "--module-name":
                    compilerArgs.add("-module-name");
                    compilerArgs.add(args[++i]);
                    break;
                case "--no-stdlib":
                    compilerArgs.add("-no-stdlib");
                    break;
                case "--no-reflect":
                    compilerArgs.add("-no-reflect");
                    break;
                case "--no-jdk":
                    compilerArgs.add("-no-jdk");
                    break;
                case "--":
                    for (int j = i + 1; j < args.length; j++) {
                        sources.add(args[j]);
                    }
                    i = args.length;
                    break;
                default:
                    throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }

        if (sources.isEmpty()) {
            throw new IllegalArgumentException("no Kotlin sources given (use -- <files...>)");
        }
        compilerArgs.addAll(sources);

        System.out.println("[KotlinBuild] sources: " + sources.size());
        org.jetbrains.kotlin.cli.jvm.K2JVMCompiler.main(compilerArgs.toArray(new String[0]));
    }

    /**
     * The first non-blank line of {@code file}, verbatim — no quoting, no escaping.
     * A UTF-8 BOM is stripped, because PowerShell likes to write one and the
     * BOM's U+FEFF would otherwise glue itself to the first drive letter.
     */
    private static String readClasspath(String file) throws Exception {
        Path p = Path.of(file);
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                return line.charAt(0) == '\uFEFF' ? line.substring(1) : line;
            }
        }
        throw new IllegalArgumentException("empty classpath file: " + file);
    }
}
