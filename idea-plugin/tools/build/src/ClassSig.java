import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A stand-in for {@code javap -p}, because the JBRs installed here ship no
 * {@code javap}.  Loads the named classes through a class loader built from a
 * classpath file and prints their declared members, so the plugin sources can be
 * compiled against the signatures that actually exist rather than the ones
 * remembered.
 *
 * Usage: java -cp tools ClassSig <classpath-file> <fqcn>...
 */
public final class ClassSig {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ClassSig <classpath-file> <fqcn>...");
            System.exit(2);
        }
        List<URL> urls = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String cpf = line.charAt(0) == '\uFEFF' ? line.substring(1) : line;
            for (String entry : cpf.split(java.io.File.pathSeparator)) {
                if (!entry.isBlank()) {
                    urls.add(Path.of(entry).toUri().toURL());
                }
            }
        }
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassSig.class.getClassLoader())) {
            for (int i = 1; i < args.length; i++) {
                describe(loader, args[i]);
            }
        }
    }

    private static void describe(ClassLoader loader, String name) {
        System.out.println("=== " + name);
        try {
            Class<?> c = Class.forName(name, false, loader);
            System.out.println("    kind: " + (c.isInterface() ? "interface" : "class")
                    + " modifiers:0x" + Integer.toHexString(c.getModifiers()));
            if (c.getSuperclass() != null) {
                System.out.println("    extends " + c.getSuperclass().getName());
            }
            for (Class<?> itf : c.getInterfaces()) {
                System.out.println("    implements " + itf.getName());
            }
            List<String> lines = new ArrayList<>();
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                lines.add("    ctor: " + sig(k));
            }
            for (Method m : c.getDeclaredMethods()) {
                lines.add("    " + (java.lang.reflect.Modifier.isAbstract(m.getModifiers()) ? "abstract " : "")
                        + (java.lang.reflect.Modifier.isStatic(m.getModifiers()) ? "static " : "")
                        + sig(m));
            }
            lines.sort(Comparator.naturalOrder());
            lines.forEach(System.out::println);
        } catch (Throwable t) {
            System.out.println("    ERROR: " + t);
        }
    }

    private static String sig(Executable e) {
        StringBuilder sb = new StringBuilder();
        if (e instanceof Method m) {
            sb.append(simple(m.getReturnType().getTypeName())).append(' ');
            sb.append(m.getName());
        } else {
            sb.append(simple(e.getDeclaringClass().getTypeName()));
        }
        sb.append('(');
        String[] ps = e.getParameterTypes().length == 0
                ? new String[0]
                : java.util.Arrays.stream(e.getParameterTypes()).map(p -> p.isArray()
                        ? simple(p.getComponentType().getTypeName()) + "[]"
                        : simple(p.getTypeName())).toArray(String[]::new);
        sb.append(String.join(", ", ps)).append(')');
        return sb.toString();
    }

    private static String simple(String typeName) {
        int i = typeName.lastIndexOf('.');
        return i < 0 ? typeName : typeName.substring(i + 1);
    }

    private ClassSig() {
    }

    @SuppressWarnings("unused")
    private static void ignore(IOException e) {
    }
}
