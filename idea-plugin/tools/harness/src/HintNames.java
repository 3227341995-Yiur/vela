import dev.vela.plugin.VelaHints;
import dev.vela.plugin.VelaModel;
import dev.vela.plugin.VelaNodeKind;
import dev.vela.plugin.VelaSyntaxNode;
import dev.vela.plugin.VelaSyntaxParser;
import dev.vela.plugin.VelaSyntaxTree;
import dev.vela.plugin.VelaSymbol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Do the *two* sources of a callable's parameter names agree?
 *
 * The hint engine and the parameter-info popup take their names from
 * `symbolParameters(sym)`, which splits `sym.detail`.  The tree has the parameters
 * themselves -- the `param` nodes the compiler's own dump prints.  If the two ever
 * disagree, the first is guessing and the second is a fact, and every such place is
 * a place where a hint could name the wrong parameter.
 *
 * So: for every callable declared in every file, compare the names the model would
 * draw against the names the tree declares.  This is the search that has to find the
 * `s: s: s:` shape, if that shape exists at all in the corpus.
 *
 *   java -cp <plugin classes> HintNames <repo-root> [--truncate <n>]
 */
public final class HintNames {

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        int step = 0;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--truncate")) step = Integer.parseInt(args[++i]);
            else root = Paths.get(args[i]).toAbsolutePath();
        }
        Set<String> files = new LinkedHashSet<>();
        for (String dir : new String[]{"tests", "examples", "ide-demo", "selfhost/parts", "bench"}) {
            Path d = root.resolve(dir);
            if (!Files.isDirectory(d)) continue;
            List<Path> found = new ArrayList<>();
            Files.walk(d)
                    .filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".vel"))
                    .forEach(found::add);
            Collections.sort(found);
            for (Path f : found) files.add(root.relativize(f).toString().replace('\\', '/'));
        }
        for (String extra : new String[]{"selfhost/vela.vel", "selfhost/vm.vel"}) {
            if (Files.isRegularFile(root.resolve(extra))) files.add(extra);
        }

        System.out.println("comparing: parameterNames(VelaModel.symbols) against the tree's param nodes");
        long compared = 0;
        long disagree = 0;
        long absent = 0;
        long tooLarge = 0;
        long threw = 0;
        long noModel = 0;
        for (String rel : files) {
            Path p = root.resolve(rel);
            if (!Files.isRegularFile(p)) {
                absent++;
                continue;
            }
            if (Files.size(p) > MAX_FILE_BYTES) {
                tooLarge++;
                continue;
            }
            String full = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            List<String> texts = new ArrayList<>();
            texts.add(full);
            if (step > 0) {
                for (int cut = 1; cut < full.length(); cut += step) texts.add(full.substring(0, cut));
            }
            for (String text : texts) {
                VelaSyntaxTree tree;
                List<VelaSymbol> symbols;
                try {
                    tree = VelaSyntaxParser.parse(text);
                    symbols = VelaModel.INSTANCE.symbols(text);
                } catch (Throwable t) {
                    threw++;
                    System.out.println("  " + rel + ": THREW " + t);
                    continue;
                }
                List<VelaSyntaxNode> defs = new ArrayList<>();
                collectDefs(tree.root, defs);
                for (VelaSyntaxNode def : defs) {
                    List<String> declared = new ArrayList<>();
                    for (VelaSyntaxNode c : def.children) {
                        if (c.kind == VelaNodeKind.PARAM) declared.add(c.name);
                    }
                    int line = lineOf(text, def.startTok >= 0 && def.startTok < tree.toks.size()
                            ? tree.toks.get(def.startTok).start : 0);
                    List<String> model = null;
                    for (VelaSymbol s : symbols) {
                        if (!s.isCallable()) continue;
                        if (!s.getName().equals(def.name)) continue;
                        if (s.getLine() != line) continue;
                        model = VelaHints.INSTANCE.parameterNames(s);
                        break;
                    }
                    if (model == null) {
                        // No entry in the symbol model matched this def, so the two sources
                        // could not be compared at all.  Counted: a def that is silently
                        // skipped is the same omission as a crash counted as success.
                        noModel++;
                        continue;
                    }
                    compared++;
                    if (!model.equals(declared)) {
                        disagree++;
                        if (disagree <= 30) {
                            System.out.println("  " + rel + (text.length() < full.length()
                                    ? " [truncated at " + text.length() + "]" : "")
                                    + " line " + line + " `" + def.name + "`  tree=" + declared
                                    + "  model=" + model);
                        }
                    }
                }
            }
        }
        System.out.println();
        System.out.println("callables compared            : " + compared);
        System.out.println("where the two sources disagree: " + disagree);
        Coverage cov = new Coverage()
                .defectCategory("threw")
                .defectCategory("missing-corpus-file")
                .category("no-model-entry-for-this-def")
                .category("too-large")
                .ran(compared)
                .skipped("threw", threw)
                .skipped("missing-corpus-file", absent)
                .skipped("no-model-entry-for-this-def", noModel)
                .skipped("too-large", tooLarge)
                .wrong(disagree);
        cov.print();
        // THE CONCLUSION, FROM THIS RUN'S OWN NUMBERS.
        //
        // This tool used to print its counts and its coverage triple and nothing else, so
        // the only place its conclusion existed was the exit code -- and a driver that read
        // the *text* of the run had nothing to read, which is how it came to be summarised
        // as "the tool did not finish" while it had just exited 0 with `wrong 0`.
        //
        // The sentence can only be a PASS under the same condition that makes the exit code
        // 0, because both are computed from `failed` below: a verdict that cannot fail is
        // not a verdict.
        List<String> failed = new ArrayList<>();
        if (disagree > 0) {
            failed.add(disagree + " of " + compared + " callable(s) name their parameters differently"
                    + " in the model than in the tree (each one listed above)");
        }
        if (cov.hasDefect()) {
            failed.add("the run is not clean either: " + cov.get("threw") + " threw, "
                    + cov.get("missing-corpus-file") + " corpus file(s) missing");
        }
        boolean clean = failed.isEmpty();
        System.out.println("VERDICT: " + (clean
                ? "[PASS] the model and the tree give the same parameter names for all "
                        + compared + " callable(s) compared (wrong 0, no defect category tripped)"
                : "[FAIL] " + String.join("; ", failed)));
        // 1 is "the two sources disagree", 3 is "the harness or the corpus is at fault".
        System.exit(clean ? 0 : (disagree > 0 ? 1 : 3));
    }

    private static void collectDefs(VelaSyntaxNode n, List<VelaSyntaxNode> out) {
        if (n.kind == VelaNodeKind.DEF) out.add(n);
        for (VelaSyntaxNode c : n.children) collectDefs(c, out);
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        int end = Math.min(offset, text.length());
        for (int i = 0; i < end; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    private HintNames() {
    }
}
