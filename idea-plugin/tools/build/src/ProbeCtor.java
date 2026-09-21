import java.lang.reflect.*;

/** Prints only the constructors (and the class header) of the named classes. */
public final class ProbeCtor {

    public static void main(String[] args) throws Exception {
        for (String name : args) {
            Class<?> c = Class.forName(name, false, ProbeCtor.class.getClassLoader());
            System.out.println("######## " + name);
            System.out.println("    super: " + (c.getSuperclass() == null ? "-" : c.getSuperclass().getTypeName())
                    + "   interfaces: " + java.util.Arrays.toString(c.getInterfaces())
                    + "   abstract=" + Modifier.isAbstract(c.getModifiers()));
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                StringBuilder b = new StringBuilder("    CTOR " + Modifier.toString(k.getModifiers()) + " " + c.getSimpleName() + "(");
                Type[] p = k.getGenericParameterTypes();
                for (int i = 0; i < p.length; i++) {
                    if (i > 0) b.append(", ");
                    b.append(p[i].getTypeName());
                }
                b.append(")");
                System.out.println(b);
            }
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().startsWith("get") && !Modifier.isStatic(m.getModifiers())) {
                    System.out.println("    GETTER " + Modifier.toString(m.getModifiers()) + " "
                            + m.getGenericReturnType().getTypeName() + " " + m.getName() + "()");
                }
            }
        }
    }
}
