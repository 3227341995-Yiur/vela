# Proof for the disagreement about whether the classic inlay API is reachable.
# Three questions, each answered by loading the class, not by reading a package name:
#   1. Does com.intellij.codeInsight.hints.FactoryInlayHintsCollector implement
#      com.intellij.codeInsight.hints.InlayHintsCollector?
#   2. Does com.intellij.codeInsight.hints.ImmediateConfigurable exist?
#   3. Is PresentationFactory reachable through a *public API* method on the base
#      class a plugin is meant to extend (getFactory), rather than by constructing
#      a non-API class?
$ErrorActionPreference = 'Continue'
$ide = 'D:\JetBrains\IntelliJ IDEA 2026.2.1'
$cp  = (Get-Content 'C:\Users\lu\Downloads\vela\idea-plugin\build\args\platform-classpath.txt' -Raw).Trim()
$out = 'C:\Users\lu\Downloads\vela\idea-plugin\build\probe-out'

$code = @'
import java.lang.reflect.*;
public class Reach {
  public static void main(String[] a) throws Exception {
    Class<?> collector = Class.forName("com.intellij.codeInsight.hints.InlayHintsCollector");
    Class<?> base = Class.forName("com.intellij.codeInsight.hints.FactoryInlayHintsCollector");
    System.out.println("[1] FactoryInlayHintsCollector implements InlayHintsCollector: "
        + collector.isAssignableFrom(base)
        + "   (abstract=" + Modifier.isAbstract(base.getModifiers()) + ")");
    for (Method m : base.getMethods()) {
      if (m.getName().equals("getFactory")) {
        System.out.println("    the instance a subclass is given: " + Modifier.toString(m.getModifiers())
            + " " + m.getGenericReturnType().getTypeName() + " " + m.getName() + "()");
        Class<?> f = m.getReturnType();
        System.out.println("    and that type implements InlayPresentationFactory: "
            + Class.forName("com.intellij.codeInsight.hints.InlayPresentationFactory").isAssignableFrom(f));
      }
    }
    try {
      Class<?> ic = Class.forName("com.intellij.codeInsight.hints.ImmediateConfigurable");
      System.out.println("[2] com.intellij.codeInsight.hints.ImmediateConfigurable EXISTS  (interface="
          + ic.isInterface() + ")");
      for (Method m : ic.getDeclaredMethods()) {
        if (Modifier.isAbstract(m.getModifiers())) {
          System.out.println("    ABSTRACT " + m.getGenericReturnType().getTypeName() + " " + m.getName());
        }
      }
    } catch (ClassNotFoundException e) {
      System.out.println("[2] MISSING: " + e);
    }
    try {
      Class.forName("com.intellij.openapi.options.ImmediateConfigurable");
      System.out.println("    (com.intellij.openapi.options.ImmediateConfigurable also exists)");
    } catch (ClassNotFoundException e) {
      System.out.println("    com.intellij.openapi.options.ImmediateConfigurable: absent, as measured before");
    }
    // The two extensions points the platform declares, and what they require.
    Class<?> provider = Class.forName("com.intellij.codeInsight.hints.InlayHintsProvider");
    System.out.println("[3] InlayHintsProvider type parameters: "
        + java.util.Arrays.toString(provider.getTypeParameters()));
    System.out.println("    FactoryInlayHintsCollector is not the declarative one: declarative collector is "
        + (Class.forName("com.intellij.codeInsight.hints.declarative.InlayHintsCollector") != collector));
  }
}
'@
Set-Content -LiteralPath "$out\Reach.java" -Value $code -Encoding UTF8
& "$ide\jbr\bin\javac.exe" -nowarn -d $out -cp $cp "$out\Reach.java" 2>&1 | Select-Object -First 5
& "$ide\jbr\bin\java.exe" -cp "$out;$cp" Reach 2>&1 | Select-String -NotMatch 'WARNING'
