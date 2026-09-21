import java.lang.reflect.*;
import java.util.*;

/**
 * Prints, for each named platform class, every declared member with its
 * modifiers, plus the set of members that are ABSTRACT and therefore must be
 * implemented.  Reading the class file's constant pool tells you a method
 * exists; only reflection tells you what Kotlin will accept as an `override`,
 * because a Kotlin interface property and a Kotlin interface method both come
 * out as a `getX()` method and only the metadata distinguishes them.
 */
public final class Probe {

    public static void main(String[] args) throws Exception {
        for (String name : args) {
            Class<?> c = Class.forName(name, false, Probe.class.getClassLoader());
            System.out.println("######## " + name);
            System.out.println("  kind: " + (c.isInterface() ? "interface" : "class")
                    + "  generic: " + Arrays.toString(c.getTypeParameters()));
            System.out.println("  supertypes: " + supertypes(c));

            System.out.println("  --- abstract (MUST be implemented by a Kotlin impl)");
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers()) && !m.isSynthetic()) {
                    System.out.println("      " + desc(m));
                }
            }
            System.out.println("  --- all declared methods");
            List<Method> all = new ArrayList<>(Arrays.asList(c.getDeclaredMethods()));
            all.sort(Comparator.comparing(Method::getName));
            for (Method m : all) {
                if (m.isSynthetic() && !m.getName().contains("access$")) continue;
                System.out.println("      " + desc(m));
            }
            System.out.println("  --- inherited from supertypes that are abstract");
            Set<String> seen = new TreeSet<>();
            for (Class<?> s : allSupertypes(c)) {
                for (Method m : s.getDeclaredMethods()) {
                    if (Modifier.isAbstract(m.getModifiers()) && !m.isSynthetic()) {
                        seen.add(s.getSimpleName() + " :: " + desc(m));
                    }
                }
            }
            seen.forEach(s -> System.out.println("      " + s));
            System.out.println();
        }
    }

    private static String desc(Method m) {
        StringBuilder b = new StringBuilder();
        b.append(Modifier.toString(m.getModifiers())).append(' ');
        b.append(m.getGenericReturnType().getTypeName()).append(' ');
        b.append(m.getName()).append('(');
        Type[] p = m.getGenericParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) b.append(", ");
            b.append(p[i].getTypeName());
        }
        b.append(')');
        for (Type g : m.getGenericExceptionTypes()) b.append(" throws ").append(g.getTypeName());
        return b.toString();
    }

    private static List<Class<?>> allSupertypes(Class<?> c) {
        List<Class<?>> out = new ArrayList<>();
        Deque<Class<?>> q = new ArrayDeque<>(Arrays.asList(c.getInterfaces()));
        while (!q.isEmpty()) {
            Class<?> s = q.poll();
            if (out.contains(s)) continue;
            out.add(s);
            q.addAll(Arrays.asList(s.getInterfaces()));
        }
        return out;
    }

    private static String supertypes(Class<?> c) {
        StringBuilder b = new StringBuilder();
        if (c.getSuperclass() != null) b.append(c.getSuperclass().getTypeName());
        for (Class<?> i : c.getInterfaces()) b.append(", ").append(i.getTypeName());
        return b.toString();
    }
}
