import dev.vela.plugin.VelaHints;
import dev.vela.plugin.VelaModel;
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
 * Where does a parameter *name* repeat inside one signature?
 *
 * `s: s: s:` at a call site can only come from one place: the parameter list the
 * hint engine read for the callee contained `s` three times.  So this asks that
 * question directly, of every symbol the model produces for every file (and of every
 * builtin), and of every truncated prefix of every file -- a half-typed file is the
 * state the user is in while typing, and it is the state in which a declaration can
 * parse differently from the finished file.
 *
 * A symbol whose parameter list repeats a name, or contains an empty name, is
 * printed with the symbol's own text, so the cause is visible rather than guessed.
 *
 *   java -cp <plugin classes> HintDupes <repo-root> [--truncate <n>]
 */
public final class HintDupes {

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        int step = 0;
        boolean old = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--truncate")) step = Integer.parseInt(args[++i]);
            else if (args[i].equals("--old")) old = true;
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
            for (Path f : found) {
                files.add(root.relativize(f).toString().replace('\\', '/'));
            }
        }
        for (String extra : new String[]{"selfhost/vela.vel", "selfhost/vm.vel"}) {
            if (Files.isRegularFile(root.resolve(extra))) files.add(extra);
        }

        System.out.println("asking: does any symbol's parameter list repeat a name?  model="
                + (old ? "the OLD token scan (what 0.1.3 shipped)" : "the tree (what ships now)"));
        long symbols = 0;
        long dupes = 0;
        long empties = 0;
        long misSliced = 0;
        long absent = 0;
        long tooLarge = 0;
        long threw = 0;
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
                try {
                    List<VelaSymbol> all = old ? VelaModel.INSTANCE.referenceSymbols(text)
                            : VelaModel.INSTANCE.symbols(text);
                    for (VelaSymbol s : all) {
                        if (!s.isCallable()) continue;
                        symbols++;
                        List<String> names = VelaHints.INSTANCE.parameterNames(s);
                        // The invariant that decides where a name comes from: the detail
                        // of a callable must start with its own name and its `(`.  A
                        // detail that starts anywhere else makes `symbolParameters` read
                        // its parameter list out of whatever text happens to follow --
                        // and a call written in the body with three same-named arguments
                        // then becomes three parameters of that name.
                        if (!s.getDetail().startsWith(s.getName() + "(")) {
                            misSliced++;
                            if (misSliced <= 20) {
                                System.out.println("  MIS-SLICED " + rel + " `" + s.getName()
                                        + "` line " + s.getLine() + " detail=`" + s.getDetail()
                                        + "` -> params " + names);
                            }
                        }
                        if (hasDuplicate(names)) {
                            dupes++;
                            if (dupes <= 40) {
                                System.out.println("  " + rel + (text.length() < full.length()
                                        ? " [truncated at " + text.length() + "]" : "")
                                        + "  " + s.getKind() + " `" + s.getName() + "` line "
                                        + s.getLine() + "  detail=`" + s.getDetail() + "`  -> "
                                        + names);
                            }
                        }
                        for (String n : names) {
                            if (n.isEmpty()) {
                                empties++;
                                System.out.println("  " + rel + " empty parameter name in `"
                                        + s.getName() + "` detail=`" + s.getDetail() + "`");
                            }
                        }
                    }
                } catch (Throwable t) {
                    threw++;
                    System.out.println("  " + rel + ": symbols() THREW " + t);
                }
            }
        }
        for (String b : BUILTINS) {
            VelaSymbol s = VelaHints.INSTANCE.builtinSymbol(b);
            if (s == null) {
                System.out.println("  builtin `" + b + "` does not resolve at all");
                continue;
            }
            List<String> names = VelaHints.INSTANCE.parameterNames(s);
            if (hasDuplicate(names)) {
                dupes++;
                System.out.println("  builtin `" + b + "` detail=`" + s.getDetail() + "` -> " + names);
            }
        }
        System.out.println("callables asked: " + symbols + "   with a repeated parameter name: " + dupes
                + "   with an empty name: " + empties + "   whose detail does not start with its"
                + " own name: " + misSliced);
        Coverage cov = new Coverage()
                .defectCategory("symbols-threw")
                .defectCategory("missing-corpus-file")
                .category("too-large")
                .ran(symbols)
                .defect("symbols-threw", threw)
                .defect("missing-corpus-file", absent)
                .skipped("too-large", tooLarge)
                // The two findings this tool exists for, both counted as wrong: a repeated
                // parameter name (the `s: s: s:` this was written about) and a detail that
                // does not start with its own name (which is *how* the repeat was produced).
                .wrong(dupes + misSliced + empties);
        cov.print();
        // THE CONCLUSION, FROM THIS RUN'S OWN NUMBERS -- see HintNames.java for the longer
        // note.  It used to be the exit code alone, so a driver that reads text had nothing
        // to read and summarised a finished, clean run as "the tool did not finish".
        long wrong = dupes + misSliced + empties;
        List<String> failed = new ArrayList<>();
        if (wrong > 0) {
            failed.add(dupes + " symbol(s) repeat a parameter name, " + misSliced
                    + " whose detail does not start with its own name, " + empties
                    + " with an empty parameter name (each one listed above)");
        }
        if (cov.hasDefect()) {
            failed.add("the run is not clean either: " + cov.get("symbols-threw")
                    + " symbols() call(s) threw, " + cov.get("missing-corpus-file")
                    + " corpus file(s) missing");
        }
        boolean clean = failed.isEmpty();
        System.out.println("VERDICT: " + (clean
                ? "[PASS] no callable in the corpus and no builtin repeats or empties a parameter"
                        + " name (" + symbols + " asked, wrong 0, no defect category tripped)"
                : "[FAIL] " + String.join("; ", failed)));
        System.exit(clean ? 0 : (wrong > 0 ? 1 : 3));
    }

    private static boolean hasDuplicate(List<String> names) {
        Set<String> seen = new LinkedHashSet<>();
        for (String n : names) {
            if (!seen.add(n)) return true;
        }
        return false;
    }

    private static final String[] BUILTINS = {
        "print", "emit_str", "emit_int", "emit_float", "emit_nl", "warn_str", "warn_int",
        "warn_nl", "len", "to_float", "to_int", "sqrt", "fabs", "floor", "pow", "abs",
        "min_int", "max_int", "min_float", "max_float", "bytes_at", "substr", "concat",
        "unescape", "intern", "interned", "argc", "arg", "read_text", "write_text", "panic",
        "now", "run_command", "env", "range",
    };

    private HintDupes() {
    }
}
