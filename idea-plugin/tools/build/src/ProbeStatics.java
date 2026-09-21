import java.lang.reflect.*;
import java.util.*;

/** Prints every static field and static method of the named classes. */
public final class ProbeStatics {

    public static void main(String[] args) throws Exception {
        for (String name : args) {
            Class<?> c = Class.forName(name, false, ProbeStatics.class.getClassLoader());
            System.out.println("######## " + name);
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    System.out.println("   FIELD  " + Modifier.toString(f.getModifiers()) + " "
                            + f.getGenericType().getTypeName() + " " + f.getName());
                }
            }
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && !m.isSynthetic()) {
                    System.out.println("   METHOD " + Modifier.toString(m.getModifiers()) + " "
                            + m.getGenericReturnType().getTypeName() + " " + m.getName() + "(...)"
                            + Arrays.toString(m.getGenericParameterTypes()));
                }
            }
        }
    }
}
