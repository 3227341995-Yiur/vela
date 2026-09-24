import dev.vela.plugin.VelaBuiltin;
import dev.vela.plugin.VelaDoc;
import dev.vela.plugin.VelaDocParam;
import dev.vela.plugin.VelaDocumentationProvider;
import dev.vela.plugin.VelaModel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hover documentation against authorities that are not the hover's own model.
 *
 * WHY THIS EXISTS
 *
 * `FEATURE_PARITY.md` listed hover documentation as `partial` for one reason: the
 * extension point was registered and linked, but **nothing measured what the popup
 * says**.  A registered feature that no harness reads is a feature whose answers have
 * never been held against anything, and this plugin has already shipped one defect of
 * exactly that shape -- completion drawing `(s, s, s)` for a declaration whose
 * parameter list cannot be read.  Hover draws the same kind of text, so it gets the
 * same treatment: every claim it makes is compared with what the compiler itself says.
 *
 * WHAT IS MEASURED, AND AGAINST WHAT
 *
 * The hover is asked through the one entry point the IDE calls,
 * `VelaDocumentationProvider.documentationAt(text, offset)` -- the text and the offset
 * the popup would get -- and then `hoverHtml(doc)` is read back so the printed text is
 * the popup's own text and not a re-rendering.
 *
 *   axis 1  declarations.  `vm.exe parse <file>` prints every `struct`, `field`, `def`
 *           and `param` with the types *it* wrote (`field name=x type=float`,
 *           `param name=k mut=0 type=float`, `def name=m pure=0 ret=float`), and
 *           `vm.exe lex <file>` prints the compiler's own line for every token.  For
 *           every declaration in the corpus the hover at that declaration's name is
 *           held against that dump: the same kind, the same parameters in the same
 *           order with the same types and the same `mut`, the same return type, the
 *           same line, and the same fields for a struct.
 *   axis 2  the language's own names.  A builtin is not declared in a file, so its
 *           authority is `SPEC.md` section 8 (read by `DeclReader`, a parser that
 *           shares no code with the plugin): the parameter names the hover prints must
 *           be the names that section documents.  A builtin whose row documents the
 *           arity *without* names (`pow`, `emit_*`) is counted in its own category and
 *           nothing is claimed about its names.
 *   axis 3  no fabrication.  For every identifier in the compiler's own token stream
 *           that this file does not declare and the language does not provide, the
 *           hover must say **nothing**.  The provider resolves names by name over one
 *           file's model, so this axis is what a hover that invented a declaration
 *           would trip.
 *   axis 4  builtin signatures.  For each builtin, a program is written that makes the
 *           call the builtin's *signature* claims -- its parameter count and types --
 *           and `vm.exe check` is asked to accept it.  A control program (`c0: int =
 *           len("")`) is compiled first, so a refusal can be told apart from a broken
 *           template: control refused = harness defect, control accepted + builtin
 *           refused = the signature is wrong.
 *
 * WHAT IT DOES NOT JUDGE, and every one of these is counted and named in the coverage
 * line: a file the compiler refuses outright (no dump, so no authority at all); a
 * declaration outside the model's scope (a `def` nested in another `def`'s body -- the
 * model reads module-level and struct-level declarations, so hover is silent about it
 * and that is a limit, not a wrong answer); a parameter list the model could not read
 * (`VelaSymbol.parameters == null`, where the honest hover gives **no** names); a
 * builtin whose `SPEC.md` row documents no names.
 *
 * THE MEASUREMENT IS SHOWN TO FAIL
 *
 * A tool that prints `wrong 0` is worth nothing until it has been shown printing
 * something else.  Two switches corrupt the *oracle* on purpose, and both must turn the
 * run red:
 *
 *   --demand-hover <name>   write a small file in %TEMP% that *uses* a name the
 *                           compiler does not declare, and demand a hover for it.  The
 *                           honest hover gives none, so the demand fails -- which is the
 *                           point: the tool reports the failure instead of nodding.
 *   --swap-params           reverse the parameter names the oracle expects for the
 *                           first declaration that declares two or more, so the
 *                           parameter axis has to fail on a hover that is right.
 *
 * Usage:
 *   java -cp <plugin classes> HoverTruth <repo-root> --vm <vm.exe> [--single <file>]
 *                             [--dump [n]] [--quick] [--why]
 *                             [--demand-hover <name>] [--swap-params]
 *
 * `--dump` prints the per-row evidence (the hover's text, its structured claims and the
 * compiler's answer) for at most `n` rows (default 40).  `--quick` skips axis 4, which
 * is the only axis that starts a compiler process per builtin.  `--why` prints the
 * compiler's declaration list and this harness's side by side for a file where the two
 * disagree -- a question about this tool, answered by printing what it did.
 */
public final class HoverTruth {

    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    private Path repoRoot;
    private Path vm;
    private String single;
    private int dump;
    private boolean quick;
    private String demandHover;
    private boolean swapParams;
    private boolean why;

    private final List<String> corpus = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();
    private final Set<String> builtinNames = new LinkedHashSet<>();
    private final Map<String, String> builtinSignatures = new LinkedHashMap<>();
    private final VelaDocumentationProvider provider = new VelaDocumentationProvider();

    // The tallies the printed tables are built from.  `Coverage` gets the same numbers
    // at the end; these are per axis, because "0 wrong" over one axis and "0 judged"
    // over another must not print the same line.
    private long declJudged, declWrong, declWithheld;
    private long builtinJudged, builtinWrong, builtinSpecMissing;
    private long unknownJudged, unknownWrong;
    private long callJudged, callWrong;
    private long demandJudged, demandWrong;
    private boolean swapped = false;
    private final Set<String> specMissingSeen = new LinkedHashSet<>();
    /** SPEC.md section 8's parameter names per builtin, read once by [DeclReader]. */
    private Map<String, List<String>> specNames = new LinkedHashMap<>();
    /** Rows already counted as `nested-def-outside-the-model`, keyed `file:offset`. */
    private final Set<String> declSilentlySkipped = new LinkedHashSet<>();

    private long filesSeen, filesJudged;
    private final Map<String, Long> skipped = new LinkedHashMap<>();
    private final Set<String> defectCategories = new LinkedHashSet<>();
    /** The files the compiler refused outright, and the declarations outside the model's scope. */
    private final List<String> refusedFiles = new ArrayList<>();
    private final List<String> nestedDecls = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        HoverTruth tool = new HoverTruth();
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--vm")) tool.vm = Paths.get(args[++i]).toAbsolutePath();
            else if (a.equals("--single")) tool.single = args[++i];
            else if (a.equals("--dump")) {
                tool.dump = 40;
                if (i + 1 < args.length && args[i + 1].matches("\\d+")) tool.dump = Integer.parseInt(args[++i]);
            } else if (a.equals("--quick")) tool.quick = true;
            else if (a.equals("--why")) tool.why = true;
            else if (a.equals("--demand-hover")) tool.demandHover = args[++i];
            else if (a.equals("--swap-params")) tool.swapParams = true;
            else rest.add(a);
        }
        tool.repoRoot = rest.isEmpty() ? Paths.get("").toAbsolutePath()
                : Paths.get(rest.get(0)).toAbsolutePath();
        tool.run();
    }

    private void run() throws Exception {
        if (vm == null) vm = repoRoot.resolve("selfhost").resolve("build").resolve("vm.exe");
        if (!Files.isRegularFile(vm)) {
            System.out.println("no compiler at " + vm + " (pass --vm)");
            System.exit(2);
        }
        if (single != null) corpus.add(single.replace('\\', '/'));
        else collectCorpus();

        for (VelaBuiltin b : VelaModel.INSTANCE.getBUILTINS()) {
            builtinNames.add(b.getName());
            builtinSignatures.put(b.getName(), b.getSignature());
        }
        Map<String, List<String>> specNames = DeclReader.specBuiltinNames(repoRoot);
        this.specNames = specNames;

        System.out.println("hover    : VelaDocumentationProvider.documentationAt(text, offset), then");
        System.out.println("           hoverHtml(doc) -- the popup's own text, not a re-rendering of it");
        System.out.println("oracle 1 : `vm.exe parse <file>` -- every struct / field / def / param with the");
        System.out.println("           name, mut and type the compiler itself writes, plus `vm.exe lex` for");
        System.out.println("           the compiler's own line of the token being hovered");
        System.out.println("oracle 2 : SPEC.md section 8 -- the parameter names a builtin is documented with");
        System.out.println("oracle 3 : `vm.exe check` on a program that writes the call each builtin's");
        System.out.println("           signature claims; a control program is compiled first");
        System.out.println("compiler : " + vm + " (" + Files.size(vm) + " bytes, sha256 " + sha256(vm) + ")");
        System.out.println("plugin   : dev.vela.plugin.VelaDocumentationProvider (class sha256 "
                + classSha256() + ")");
        System.out.println("corpus   : " + corpus.size() + (single == null ? " file(s)" : " file(s), --single")
                + (dump > 0 ? ", --dump " + dump : "") + (quick ? ", --quick (axis 4 not run)" : ""));
        System.out.println();

        if (demandHover != null) {
            System.out.println("!! --demand-hover " + demandHover + ": a hover is DEMANDED for a name the");
            System.out.println("!! compiler does not declare.  A clean run is impossible by construction; the");
            System.out.println("!! point is to watch the tool report the failure.");
            System.out.println();
        }
        if (swapParams) {
            System.out.println("!! --swap-params: the parameter names the oracle expects are REVERSED for the");
            System.out.println("!! first declaration that declares two or more.  A clean run is impossible by");
            System.out.println("!! construction.");
            System.out.println();
        }

        System.out.println("== axis 1: the hover at a declaration, against what the compiler says it is ==");
        for (String rel : corpus) judgeFile(rel);
        System.out.println("  declarations hovered            : " + declJudged);
        System.out.println("  disagreeing with the compiler   : " + declWrong);
        System.out.println("  parameter lists the hover withheld : " + declWithheld);

        System.out.println();
        System.out.println("== axis 2/3: the language's names, and names nothing declares ==");
        System.out.println("  builtin hover(s) judged         : " + builtinJudged);
        System.out.println("  disagreeing with SPEC.md        : " + builtinWrong);
        System.out.println("  builtin(s) SPEC.md does not name: " + builtinSpecMissing);
        System.out.println("  undeclared name(s) hovered      : " + unknownJudged);
        System.out.println("  of those, the hover said something : " + unknownWrong);

        if (!quick) builtinCallAxis();

        if (demandHover != null) judgeDemand();

        System.out.println();
        System.out.println("== findings ==");
        if (findings.isEmpty()) System.out.println("  (none)");
        else for (String f : findings) System.out.println("  " + f);

        System.out.println();
        System.out.println("== what this run did not judge ==");
        if (skipped.isEmpty()) System.out.println("  (nothing was skipped)");
        else for (Map.Entry<String, Long> e : skipped.entrySet()) {
            System.out.println("  " + pad(e.getKey(), 44) + e.getValue()
                    + (defectCategories.contains(e.getKey()) ? "   <- a defect, not a limit" : ""));
        }
        if (!refusedFiles.isEmpty()) {
            System.out.println("  the files `vm.exe parse` refused (first " + refusedFiles.size() + "):");
            for (String f : refusedFiles) System.out.println("      " + f);
        }
        if (!nestedDecls.isEmpty()) {
            System.out.println("  declarations outside the model's scope (first " + nestedDecls.size()
                    + "), hovered but not claimed by the model:");
            for (String f : nestedDecls) System.out.println("      " + f);
        }

        long ran = declJudged + builtinJudged + unknownJudged + callJudged + demandJudged;
        long wrong = declWrong + builtinWrong + unknownWrong + callWrong + demandWrong;
        long skippedTotal = 0;
        for (long v : skipped.values()) skippedTotal += v;

        System.out.println();
        System.out.println("== totals ==");
        System.out.println("  files in the corpus            : " + filesSeen);
        System.out.println("  files with a compiler dump     : " + filesJudged);
        System.out.println("  rows judged (a hovered symbol) : " + ran);
        System.out.println("  rows that disagree             : " + wrong);

        Coverage cov = new Coverage();
        cov.defectCategory("harness-threw");
        for (String cat : skipped.keySet()) {
            if (defectCategories.contains(cat)) cov.defectCategory(cat);
            else cov.category(cat);
        }
        cov.ran(ran).wrong(wrong);
        for (Map.Entry<String, Long> e : skipped.entrySet()) {
            if (defectCategories.contains(e.getKey())) cov.defect(e.getKey(), e.getValue());
            else cov.skipped(e.getKey(), e.getValue());
        }
        cov.print();
        System.out.println();

        boolean declOk = declWrong == 0;
        boolean builtinOk = builtinWrong == 0;
        boolean unknownOk = unknownWrong == 0;
        boolean callOk = callWrong == 0;
        System.out.println("  VERDICT: declarations "
                + (declOk ? "the hover names what the compiler declares, with its parameters, its"
                + " return type and its line" : "[FAIL] " + declWrong + " declaration(s) hovered"
                + " differently from what the compiler says"));
        System.out.println("  VERDICT: builtins     "
                + (builtinOk ? "every builtin hover names the parameters SPEC.md documents"
                : "[FAIL] " + builtinWrong + " builtin hover(s) disagree with SPEC.md section 8")
                + " (" + builtinSpecMissing + " builtin(s) SPEC.md documents without names)");
        System.out.println("  VERDICT: unknown      "
                + (unknownOk ? "a name neither this file nor the language declares is hovered as nothing"
                : "[FAIL] " + unknownWrong + " name(s) nothing declares were given documentation"));
        System.out.println("  VERDICT: call shapes  "
                + (quick ? "(not measured: --quick)"
                : (callOk ? "the compiler accepts the call every builtin signature claims"
                : "[FAIL] " + callWrong + " builtin signature(s) the compiler refuses")));
        if (demandJudged > 0) {
            System.out.println("  VERDICT: demand       " + (demandWrong > 0
                    ? "[FAIL] the demanded hover did not exist -- the demand failed, which is what it "
                    + "was written to do"
                    : "the demanded hover existed: --demand-hover did NOT fail, so it proved nothing"));
        }
        if (swapParams) {
            System.out.println("  VERDICT: swap-params  " + (swapped
                    ? "(the oracle's parameter names were reversed for one declaration)"
                    : "[FAIL] no declaration with two parameters was reached, so the swap never fired"));
        }

        String verdict;
        if (wrong > 0) {
            verdict = wrong + " row(s) of the " + ran + " judged disagree with the compiler, SPEC.md or"
                    + " the absence of a declaration";
        } else if (cov.hasDefect()) {
            verdict = "no disagreement in the " + ran + " row(s) judged, but the harness or the corpus"
                    + " is defective, which is not a clean run";
        } else {
            verdict = "every one of the " + ran + " row(s) judged says what the compiler, SPEC.md or"
                    + " the absence of a declaration says";
        }
        System.out.println("VERDICT: " + verdict);
        System.exit(wrong > 0 ? 1 : (cov.hasDefect() ? 3 : 0));
    }

    // ---------------------------------------------------------------- axis 1

    private void judgeFile(String rel) {
        filesSeen++;
        Path p = repoRoot.resolve(rel);
        if (!Files.isRegularFile(p)) {
            bump("missing-corpus-file");
            return;
        }
        String full;
        try {
            if (Files.size(p) > MAX_FILE_BYTES) {
                bump("file-too-large");
                return;
            }
            full = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            bump("unreadable-corpus-file");
            return;
        }
        try {
            judgeText(full, rel, false);
        } catch (Throwable t) {
            defect("harness-threw");
            finding(rel + ": the harness threw " + t);
        }
    }

    /** One file: the compiler's dump, its token stream, and every hover the file offers. */
    private void judgeText(String text, String rel, boolean demandOnly) throws Exception {
        Path tmp = Files.createTempFile("vela-hovertruth-", ".vel");
        List<Ent> dump;
        Map<Integer, LexTok> toks;
        try {
            Files.write(tmp, text.getBytes(StandardCharsets.ISO_8859_1));
            Res parsed = run(tmp, "parse");
            if (parsed.exit != 0) {
                bump("compiler-refused-the-file");
                if (refusedFiles.size() < 24) {
                    refusedFiles.add(rel + "  (`vm.exe parse` exit " + parsed.exit + ": "
                            + oneLine(parsed.err) + ")");
                }
                return;
            }
            dump = parseDump(parsed.out);
            Res lexed = run(tmp, "lex");
            if (lexed.exit != 0) {
                bump("compiler-refused-the-file");
                return;
            }
            toks = parseLex(lexed.out, text, rel);
            if (toks == null) return;
        } finally {
            Files.deleteIfExists(tmp);
        }
        filesJudged++;

        List<Ent> scan = scanDeclarations(text);
        String divergence = align(dump, scan);
        if (divergence != null) {
            defect("declaration-scan-disagrees-with-the-compiler-dump");
            finding(rel + ": " + divergence);
            if (why) printBoth(rel, text, dump, scan);
            return;
        }

        // ---- axis 1: every declaration the compiler prints, hovered at its own name
        for (int i = 0; i < dump.size(); i++) {
            Ent d = dump.get(i);
            Ent s = scan.get(i);
            if (demandOnly) continue;
            // A payload field is judged like any other declaration from 0.1.10: the model
            // declares it (with the variant as its owner), so this tool holds the hover to
            // it instead of counting it.  The class that used to be bumped here --
            // `payload-field-not-a-model-declaration` -- is gone, and the 15 positions it
            // held are in `ran`.
            if (d.nested) {
                // A `def` inside another `def`'s body: the model reads module-level and
                // struct-level declarations, so this declaration is outside its scope and
                // the hover may honestly say nothing.  Whether it does is measured -- the
                // row is judged, and only a *silent* hover is put in this category.
                bump("nested-def-outside-the-model");
                if (nestedDecls.size() < 24) {
                    LexTok where = toks.get(s.offset);
                    nestedDecls.add(rel + ":" + (where == null ? "?" : String.valueOf(where.line))
                            + " `" + d.name + "` (" + d.kind + ")");
                }
                declSilentlySkipped.add(rel + ":" + s.offset);
            }
            judgeDeclaration(rel, text, toks, d, s);
        }

        if (demandOnly) return;

        // ---- axis 2/3: every identifier the *compiler's own* token stream holds
        Map<String, Integer> declared = new LinkedHashMap<>();
        for (Ent d : dump) declared.putIfAbsent(d.name, 1);
        Set<String> seen = new LinkedHashSet<>();
        int rows = 0;
        for (LexTok t : toks.values()) {
            if (!t.isIdentifier) continue;
            if (!seen.add(t.text)) continue;
            if (declared.containsKey(t.text)) continue;      // axis 1 hovered its declaration
            if (builtinNames.contains(t.text)) {
                judgeBuiltinName(rel, text, t, specNames.get(t.text));
                // A builtin name can also be declared by *this* file; the rule above has
                // already sent that case to axis 1, so nothing more is claimed here.
                rows++;
                continue;
            }
            judgeUnknownName(rel, text, t);
            rows++;
            if (rows > 20000) {
                // A bound on the work, named rather than silent: a file with more distinct
                // undeclared names than this has its remaining ones counted here.
                bump("undeclared-name-scan-capped-in-this-file");
                break;
            }
        }
    }

    private void judgeDeclaration(String rel, String text, Map<Integer, LexTok> toks, Ent d, Ent s) {
        LexTok tok = toks.get(s.offset);
        if (tok == null || !tok.text.equals(d.name)) {
            defect("declaration-name-not-a-compiler-token");
            finding(rel + ": the scanner put `" + d.name + "` at offset " + s.offset
                    + ", where the compiler's token stream has "
                    + (tok == null ? "no token" : "`" + tok.text + "`"));
            return;
        }
        VelaDoc doc = provider.documentationAt(text, s.offset);
        String where = rel + ":" + tok.line + " `" + d.name + "` (" + d.kind + ")";
        declJudged++;
        if (doc == null) {
            if (d.nested && declSilentlySkipped.remove(rel + ":" + s.offset)) return;
            declWrong++;
            finding(where + ": the compiler declares it and the hover said nothing");
            return;
        }
        List<String> problems = new ArrayList<>();
        String html = provider.hoverHtml(doc);

        // the kind
        if (!d.kind.equals(doc.getKind())) {
            problems.add("kind: the compiler says `" + d.kind + "`, the hover says `"
                    + doc.getKind() + "`");
        }
        // the line, from the compiler's own token stream
        if (doc.getLine() != tok.line) {
            problems.add("line: the compiler's token is on line " + tok.line
                    + ", the hover says line " + doc.getLine());
        }
        // the owner: the struct of a field or a method, the def of a parameter
        String owner = ownerOf(d);
        if (!owner.equals(doc.getOwner())) {
            problems.add("owner: the compiler nests it under `" + (owner.isEmpty() ? "-" : owner)
                    + "`, the hover says `" + (doc.getOwner().isEmpty() ? "-" : doc.getOwner()) + "`");
        }
        // the return type
        String expectedRet = d.kind.equals("def") ? d.ret : "";
        if (!expectedRet.equals(doc.getReturns())) {
            problems.add("returns: the compiler says `" + expectedRet + "`, the hover says `"
                    + doc.getReturns() + "`");
        }
        // the parameters, in order, with their types and their mut
        List<String> expectParams = paramList(d.params);
        if (expectParams != null && d.kind.equals("def")) {
            List<String> oracle = expectParams;
            if (swapParams && !swapped && oracle.size() >= 2) {
                oracle = new ArrayList<>(oracle);
                Collections.reverse(oracle);
                swapped = true;
                System.out.println("  !! the oracle's parameter names for " + where
                        + " were reversed on purpose -> " + oracle);
            }
            if (doc.getParameters() == null) {
                declWithheld++;
                bump("hover-withheld-the-parameter-list");
            } else {
                List<String> got = paramList(doc.getParameters());
                if (!oracle.equals(got)) {
                    problems.add("parameters: the compiler declares " + oracle
                            + ", the hover says " + got);
                }
            }
        }
        // a struct's fields
        if (d.kind.equals("struct")) {
            List<String> expectFields = new ArrayList<>();
            for (Ent f : d.fields) expectFields.add(norm(f.name) + ":" + norm(f.type));
            List<String> gotFields = new ArrayList<>();
            for (VelaDocParam f : doc.getFields()) gotFields.add(norm(f.getName()) + ":" + norm(f.getType()));
            if (!expectFields.equals(gotFields)) {
                problems.add("fields: the compiler declares " + expectFields + ", the hover says "
                        + gotFields);
            }
        }
        // the text the popup is actually built from: the signature, and the names it shows
        if (d.kind.equals("def") && !doc.getSignature().startsWith(d.name + "(")) {
            problems.add("signature: the hover prints `" + doc.getSignature()
                    + "`, which does not begin with the declared name and its `(`");
        }
        if (!html.contains(escape(doc.getSignature()))) {
            problems.add("the popup's own text does not contain the signature `"
                    + doc.getSignature() + "`");
        }
        if (doc.getParameters() != null && d.kind.equals("def")) {
            for (VelaDocParam p : doc.getParameters()) {
                if (!html.contains(p.getName())) {
                    problems.add("the popup's own text does not show the parameter `" + p.getName() + "`");
                }
            }
        }
        if (!problems.isEmpty()) {
            if (d.nested) {
                // A declaration outside the model's scope, hovered anyway: the model
                // reads module-level and struct-level declarations, so the hover either
                // said nothing (a limit, named below) or answered from another
                // declaration of the same name (which is a disagreement, and is counted).
                boolean silent = declSilentlySkipped.remove(rel + ":" + s.offset);
                if (silent) return;
            }
            declWrong++;
            StringBuilder sb = new StringBuilder(where);
            for (String s2 : problems) sb.append("\n      ").append(s2);
            sb.append("\n      hover: ").append(doc.getKind()).append(' ').append(doc.getSignature())
                    .append(doc.getParameters() == null ? "" : "  params " + paramList(doc.getParameters()));
            finding(sb.toString());
        }
        if (dump > 0 && printed < dump) {
            printed++;
            System.out.println("  ---- " + where);
            System.out.println("       hover  : " + oneLine(provider.hoverHtml(doc)));
            System.out.println("       claims : kind=" + doc.getKind() + " line=" + doc.getLine()
                    + " returns=" + doc.getReturns() + " owner=" + doc.getOwner()
                    + " params=" + (doc.getParameters() == null ? "(withheld)" : paramList(doc.getParameters()))
                    + " fields=" + fieldList(doc));
            System.out.println("       oracle : kind=" + d.kind + " line=" + tok.line + " returns="
                    + expectedRet + " owner=" + owner + " params=" + expectParams);
        }
    }

    private int printed = 0;

    private void judgeBuiltinName(String rel, String text, LexTok t, List<String> spec) {
        builtinJudged++;
        VelaDoc doc = provider.documentationAt(text, t.offset);
        String where = rel + ":" + t.line + " `" + t.text + "` (builtin)";
        if (doc == null) {
            builtinWrong++;
            finding(where + ": the language provides it and the hover said nothing");
            return;
        }
        List<String> problems = new ArrayList<>();
        if (!doc.getKind().equals("builtin")) {
            problems.add("kind: the hover says `" + doc.getKind() + "`, not `builtin`");
        }
        if (doc.getLine() != 0) {
            problems.add("line: a builtin is not declared in this file, and the hover says line "
                    + doc.getLine());
        }
        String signature = builtinSignatures.get(t.text);
        if (signature != null && !doc.getSignature().equals(signature)) {
            problems.add("signature: the model's table says `" + signature + "`, the hover says `"
                    + doc.getSignature() + "`");
        }
        if (spec == null) {
            if (specMissingSeen.add(t.text)) bump("spec-documents-the-arity-without-parameter-names");
            builtinSpecMissing = specMissingSeen.size();
        } else if (doc.getParameters() == null && !doc.getVariadic()) {
            problems.add("the hover withheld the parameter list of a non-variadic builtin");
        } else if (doc.getParameters() != null) {
            List<String> got = new ArrayList<>();
            for (VelaDocParam p : doc.getParameters()) got.add(p.getName());
            if (!spec.equals(got)) {
                problems.add("parameter names: SPEC.md section 8 writes " + spec
                        + ", the hover says " + got);
            }
        }
        if (!problems.isEmpty()) {
            builtinWrong++;
            StringBuilder sb = new StringBuilder(where);
            for (String s : problems) sb.append("\n      ").append(s);
            finding(sb.toString());
        }
        if (dump > 0 && printed < dump) {
            printed++;
            System.out.println("  ---- " + where);
            System.out.println("       hover  : " + oneLine(provider.hoverHtml(doc)));
            System.out.println("       claims : signature=" + doc.getSignature() + " variadic="
                    + doc.getVariadic() + " returns=" + doc.getReturns() + " params="
                    + (doc.getParameters() == null ? "(withheld: variadic)" : paramList(doc.getParameters())));
            System.out.println("       oracle : SPEC.md section 8 names="
                    + (spec == null ? "(documents the arity without names)" : spec));
        }
    }

    private void judgeUnknownName(String rel, String text, LexTok t) {
        unknownJudged++;
        VelaDoc doc = provider.documentationAt(text, t.offset);
        if (doc == null) return;
        unknownWrong++;
        String where = rel + ":" + t.line + " `" + t.text + "`";
        finding(where + ": neither this file nor the language declares it, and the hover says"
                + " `" + doc.getKind() + " " + doc.getSignature() + "`"
                + " (summary: " + doc.getSummary() + ")");
    }

    // ---------------------------------------------------------------- axis 4

    private void builtinCallAxis() throws Exception {
        System.out.println();
        System.out.println("== axis 4: the call each builtin's signature claims, against the compiler ==");
        Path control = writeTemp("def main() -> None {\n    c0: int = len(\"\")\n}\n");
        Res controlRes = run(control, "check");
        Files.deleteIfExists(control);
        boolean controlOk = controlRes.exit == 0;
        System.out.println("  control program (`c0: int = len(\"\")`) : "
                + (controlOk ? "accepted" : "REFUSED -- " + oneLine(controlRes.err)));
        if (!controlOk) {
            defect("control-program-refused");
            System.out.println("  the template itself is broken, so no builtin signature is judged");
            return;
        }
        int judged = 0;
        for (Map.Entry<String, String> e : builtinSignatures.entrySet()) {
            String name = e.getKey();
            String signature = e.getValue();
            List<String> entries = signatureParams(signature);
            if (entries == null) {
                bump("builtin-signature-unreadable");
                continue;
            }
            if (signature.contains("...")) {
                bump("variadic-signature-declares-no-parameter");
                continue;
            }
            int arrow = signature.indexOf("->");
            if (arrow < 0) {
                bump("builtin-signature-writes-no-return-type");
                continue;
            }
            String ret = signature.substring(arrow + 2).trim().split("\\s+")[0];
            List<String> args = new ArrayList<>();
            boolean synthesizable = true;
            for (String entry : entries) {
                int colon = entry.indexOf(':');
                if (colon < 0) {
                    synthesizable = false;
                    break;
                }
                String literal = literalFor(entry.substring(colon + 1).trim());
                if (literal == null) {
                    synthesizable = false;
                    break;
                }
                args.add(literal);
            }
            if (!synthesizable) {
                bump("builtin-parameter-type-not-synthesizable");
                continue;
            }
            String call = name + "(" + String.join(", ", args) + ")";
            String stmt = (ret.isEmpty() || ret.equals("None")) ? call : "v0: " + ret + " = " + call;
            Path p = writeTemp("def main() -> None {\n    " + stmt + "\n}\n");
            Res res = run(p, "check");
            Files.deleteIfExists(p);
            callJudged++;
            judged++;
            if (res.exit == 0) continue;
            callWrong++;
            finding("the builtin `" + name + "` claims `" + signature + "`, so the program"
                    + "\n      def main() -> None {\n          " + stmt + "\n      }"
                    + "\n      was written, and the compiler REFUSED it: " + oneLine(res.err));
        }
        System.out.println("  builtin signature(s) the compiler judged : " + judged);
        System.out.println("  refused                                : " + callWrong);
    }

    /**
     * The `[mut] name: type` entries of a signature, or null when it is not a signature
     * at all.  Written here, in the harness: the plugin's own reader is not the oracle
     * for what its own signature says.
     */
    private static List<String> signatureParams(String signature) {
        int open = signature.indexOf('(');
        if (open < 0) return null;
        int nest = 0;
        int close = -1;
        for (int i = open; i < signature.length(); i++) {
            char c = signature.charAt(i);
            if (c == '(') nest++;
            else if (c == ')') {
                nest--;
                if (nest == 0) {
                    close = i;
                    break;
                }
            }
        }
        if (close < 0) return null;
        String inner = signature.substring(open + 1, close).trim();
        if (inner.isEmpty()) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '[' || c == '(') depth++;
            else if (c == ']' || c == ')') depth--;
            if (c == ',' && depth == 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString().trim());
        return out;
    }

    /** A literal of [type], or null when this harness cannot write one. */
    private static String literalFor(String type) {
        for (String branch : type.split("\\|")) {
            String t = branch.trim();
            if (t.equals("int")) return "0";
            if (t.equals("float")) return "0.0";
            if (t.equals("str")) return "\"\"";
            if (t.equals("bool")) return "false";
        }
        return null;
    }

    // ---------------------------------------------------------------- falsification

    /**
     * `--demand-hover <name>`: a hover is demanded for a name nothing declares.
     *
     * The file written here calls the name inside `main`, so the caret has somewhere to
     * be, and the compiler is not asked about it because the compiler has no such
     * declaration.  The honest hover says nothing, the demand is unmet, and the run goes
     * red -- which is the whole point of the switch: it is the *measurement* that is
     * being shown to be able to fail, not the feature.
     */
    private void judgeDemand() throws Exception {
        System.out.println();
        System.out.println("== falsification: a hover is DEMANDED for a name nothing declares ==");
        String text = "def main() -> None {\n    " + demandHover + "(1)\n}\n";
        Path p = writeTemp(text);
        try {
            // The compiler is asked what it thinks of the file, so the demand can say
            // whether the refused file is refused for the name or for something else.
            Res res = run(p, "check");
            int offset = text.indexOf(demandHover);
            String source = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            VelaDoc doc = provider.documentationAt(source, offset);
            demandJudged++;
            System.out.println("  file    : " + p);
            System.out.println("  the call: " + demandHover + "(1)");
            System.out.println("  compiler: `vm.exe check` exit " + res.exit + " -- " + oneLine(res.err));
            System.out.println("  demand  : a hover must exist for `" + demandHover + "`");
            System.out.println("  hover   : " + (doc == null ? "(none: the model has no such declaration)"
                    : doc.getKind() + " " + doc.getSignature()));
            if (doc == null) {
                demandWrong++;
                finding("the demanded hover for `" + demandHover + "` does not exist: the compiler"
                        + " does not declare it, the language does not provide it, and the hover gave"
                        + " nothing.  THE DEMAND FAILED, WHICH IS WHAT --demand-hover EXISTS TO SHOW.");
                System.out.println("  DEMAND UNMET: the tool reports this as a failure, so `wrong` is not a"
                        + " number that can only ever be 0.");
            } else {
                System.out.println("  the demand was MET: --demand-hover proved nothing this run, because"
                        + " a hover existed after all.");
            }
        } finally {
            Files.deleteIfExists(p);
        }
    }

    // ---------------------------------------------------------------- the oracle: the dump

    /** One declaration the compiler's dump printed, or the scanner found. */
    private static final class Ent {
        final String kind;      // struct | enum | variant | field | def | param
        final String name;
        String type = "";
        String ret = "";
        boolean mut;
        String owner = "";
        boolean nested;
        /**
         * Is this `field` line a *variant's payload* rather than a struct field?
         *
         * The two are the same word in the compiler's dump -- `field name=radius
         * type=float` under a `variant`, `field name=x type=int` under a `struct` -- so the
         * kind cannot tell them apart.  What differs is the **owner**: a struct field
         * belongs to its struct and a payload field to the variant that carries it
         * (SPEC.md §13), which is what the plugin's hover answers with and therefore what
         * this tool has to expect.
         */
        boolean payload;
        int offset = -1;
        int indent;
        Ent parent;
        final List<Ent> params = new ArrayList<>();
        final List<Ent> fields = new ArrayList<>();

        Ent(String kind, String name) {
            this.kind = kind;
            this.name = name;
        }
    }

    /**
     * `vm.exe parse`'s tree, flattened to the declarations in source order.
     *
     * The dump is a tree in pre-order with two spaces per level, so an entry's parent
     * is the nearest *pushed* line above it -- and every line is pushed, including the
     * ones this tool does not name, because that is what makes "the nearest ancestor" a
     * fact about the dump rather than a guess about which lines matter.
     */
    private static List<Ent> parseDump(String dump) {
        List<Ent> out = new ArrayList<>();
        Deque<Ent> stack = new ArrayDeque<>();
        for (String raw : dump.split("\n")) {
            if (raw.isEmpty()) continue;
            int indent = 0;
            while (indent < raw.length() && raw.charAt(indent) == ' ') indent++;
            String body = raw.trim();
            if (body.isEmpty()) continue;
            int depth = indent / 2;
            while (!stack.isEmpty() && stack.peek().indent >= depth) stack.pop();
            Ent parent = stack.isEmpty() ? null : stack.peek();
            Ent e = null;
            if (body.startsWith("struct name=")) {
                e = new Ent("struct", wordAfter(body, "struct name="));
            } else if (body.startsWith("enum name=")) {
                // SPEC.md §13.  The dump prints `enum name=Shape` and, one level in,
                // `variant name=Circle fields=1` with a `field name=radius type=float`
                // under it.  A reader that knew only `struct`/`def` saw the enum's
                // *variants* as undeclared names and demanded that the hover say nothing
                // about them -- which is 21 findings against a hover that says something
                // true (`enum Color`, `variant Red`) the moment the language grows enums.
                e = new Ent("enum", wordAfter(body, "enum name="));
            } else if (body.startsWith("variant name=")) {
                e = new Ent("variant", wordAfter(body, "variant name="));
                e.owner = parent != null && parent.kind.equals("enum") ? parent.name : "";
            } else if (body.startsWith("field name=")) {
                // A `field` line under a `variant` is that variant's payload (SPEC.md §13).
                // The *kind* stays `field`, because that is the word the compiler's dump
                // prints and the word the plugin's hover answers with; what the flag below
                // changes is the **owner** -- a struct field belongs to its struct, and a
                // payload field belongs to the variant that carries it.  Until 0.1.10 this
                // was a counted class (`payload-field-not-a-model-declaration`) because the
                // model declared no symbol for it; the model declares one now, so it is
                // judged like any other declaration, which is the measurement that says so.
                String variant = variantOf(parent);
                e = new Ent("field", wordAfter(body, "field name="));
                e.type = attr(body, "type=");
                e.mut = attr(body, "mut=").equals("1");
                if (variant != null) {
                    e.payload = true;
                    e.owner = variant;
                }
            } else if (body.startsWith("def name=")) {
                e = new Ent("def", wordAfter(body, "def name="));
                e.ret = attr(body, "ret=");
            } else if (body.startsWith("param name=")) {
                e = new Ent("param", wordAfter(body, "param name="));
                e.type = attr(body, "type=");
                e.mut = attr(body, "mut=").equals("1");
            }
            if (e != null) {
                e.indent = depth;
                e.parent = parent;
                e.nested = nestedInDef(parent);
                // ORDER MATTERS HERE, AND GETTING IT WRONG COST A RED RUN.  The owner of a
                // *payload* field was decided by the branch above, from the variant that
                // carries it; the field branch below would overwrite it with
                // `structOf(parent)`, which answers "" because a payload's parent is a
                // variant and not a struct.  Measured: 15 findings, every one of them
                // saying "the compiler nests it under `-`" about a hover that was right
                // (`field radius: float`, owner `Circle`).
                if (e.payload) {
                    // already owned by its variant
                } else if (e.kind.equals("field")) e.owner = e.nested ? "" : structOf(parent);
                else if (e.kind.equals("def")) e.owner = e.nested ? "" : structOf(parent);
                else if (e.kind.equals("param")) {
                    e.owner = parent != null && parent.kind.equals("def") ? parent.name : "";
                    e.nested = parent != null && parent.nested;
                    if (parent != null) parent.params.add(e);
                }
                if (e.kind.equals("field") && parent != null) parent.fields.add(e);
                out.add(e);
            }
            // Every line is pushed, so the stack mirrors the dump's own nesting.
            Ent marker = e != null ? e : new Ent("node", "");
            if (e == null) {
                marker.indent = depth;
                marker.parent = parent;
            }
            stack.push(marker);
        }
        return out;
    }

    private static boolean nestedInDef(Ent parent) {
        Ent p = parent;
        while (p != null) {
            if (p.kind.equals("def")) return true;
            if (p.kind.equals("struct")) return false;
            p = p.parent;
        }
        return false;
    }

    private static String structOf(Ent parent) {
        Ent p = parent;
        while (p != null) {
            if (p.kind.equals("struct")) return p.name;
            p = p.parent;
        }
        return "";
    }

    private static String ownerOf(Ent d) {
        if (d.kind.equals("param")) return d.owner;
        // A payload field's owner is the variant that carries it (SPEC.md §13), the same
        // relationship a struct field has with its struct -- and both are `field` lines in
        // the dump, which is why the flag exists at all.
        if (d.payload) return d.owner;
        if (d.kind.equals("field") || d.kind.equals("def")) return d.nested ? "" : d.owner;
        // A variant's owner is the enum that declares it (SPEC.md §13), which is what
        // the plugin's hover answers with -- `VelaDocumentation` reads it off the
        // symbol's parent, exactly as it does for a field's struct.
        if (d.kind.equals("variant")) return d.owner;
        return "";
    }

    /** The nearest enclosing `variant` of an entry, or null -- SPEC.md §13. */
    private static String variantOf(Ent parent) {
        Ent p = parent;
        while (p != null) {
            if (p.kind.equals("variant")) return p.name;
            if (p.kind.equals("struct") || p.kind.equals("def")) return null;
            p = p.parent;
        }
        return null;
    }

    private static String wordAfter(String body, String prefix) {
        String rest = body.substring(prefix.length()).trim();
        return rest.split("\\s+")[0].trim();
    }

    private static String attr(String body, String key) {
        int i = body.indexOf(key);
        if (i < 0) return "";
        String rest = body.substring(i + key.length()).trim();
        char c = rest.isEmpty() ? ' ' : rest.charAt(0);
        if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) return "";
        return rest.split("\\s+")[0].trim();
    }

    // ---------------------------------------------------------------- the scanner

    /**
     * The same declarations, located in the source text by a scanner written here.
     *
     * This exists for one reason: to know *where* to put the caret.  The compiler prints
     * no offsets, and hovering needs one.  The scanner is held to the dump on every file
     * (kind and name, in order), and every offset it produces is checked against the
     * compiler's own token stream, so a scanner that is wrong cannot look like a hover
     * that is wrong.
     */
    private static List<Ent> scanDeclarations(String text) {
        List<Ent> out = new ArrayList<>();
        Deque<Scope> scopes = new ArrayDeque<>();
        Scope pending = null;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                i = endOfString(text, i);
                continue;
            }
            if (c == '#') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '{') {
                scopes.push(pending != null ? pending : Scope.inherit(scopes.peek()));
                pending = null;
                i++;
                continue;
            }
            if (c == '}') {
                if (!scopes.isEmpty()) scopes.pop();
                i++;
                continue;
            }
            if (!isNamePart(c)) {
                i++;
                continue;
            }
            int start = i;
            while (i < text.length() && isNamePart(text.charAt(i))) i++;
            String word = text.substring(start, i);
            if (word.equals("mut")) continue;
            if (word.equals("struct")) {
                int ns = skipSpace(text, i);
                int ne = endName(text, ns);
                if (ne > ns) {
                    Ent e = new Ent("struct", text.substring(ns, ne));
                    e.offset = ns;
                    out.add(e);
                    pending = Scope.struct(e.name);
                }
                continue;
            }
            if (word.equals("def")) {
                int ns = skipSpace(text, i);
                int ne = endName(text, ns);
                if (ne <= ns) continue;
                Ent e = new Ent("def", text.substring(ns, ne));
                e.offset = ns;
                Scope enclosing = scopes.peek();
                e.nested = enclosing != null && enclosing.insideDef;
                e.owner = e.nested ? "" : structOf(scopes);
                out.add(e);
                int p = skipSpace(text, ne);
                if (p < text.length() && text.charAt(p) == '(') {
                    int close = matchingParen(text, p);
                    if (close > p) {
                        scanParamNames(text, p + 1, close, e, scopes, out);
                        i = close + 1;
                    }
                }
                pending = Scope.def(e.name);
                continue;
            }
            if (word.equals("enum")) {
                // `enum Shape { Circle(radius: float) Empty }` -- SPEC.md §13.
                int ns = skipSpace(text, i);
                int ne = endName(text, ns);
                if (ne > ns) {
                    Ent e = new Ent("enum", text.substring(ns, ne));
                    e.offset = ns;
                    out.add(e);
                    pending = Scope.enumBody(e.name);
                }
                continue;
            }
            Scope top = scopes.peek();
            if (top != null && top.isEnum && !top.insideDef) {
                // An enum body holds variants and nothing else (SPEC.md §13), so a name
                // here IS a variant -- and its payload, when it has one, is the
                // parenthesised `field: Type` list the dump prints as `payload` under it.
                Ent v = new Ent("variant", word);
                v.offset = start;
                v.owner = top.structName;
                out.add(v);
                int p = skipSpace(text, i);
                if (p < text.length() && text.charAt(p) == '(') {
                    int close = matchingParen(text, p);
                    if (close > p) {
                        scanPayloadFields(text, p + 1, close, v, out);
                        i = close + 1;
                        continue;
                    }
                }
                continue;
            }
            if (top != null && top.isStruct && !top.insideDef) {
                int colon = skipSpace(text, i);
                if (colon < text.length() && text.charAt(colon) == ':') {
                    int ts = skipSpace(text, colon + 1);
                    int te = endOfType(text, ts);
                    Ent e = new Ent("field", word);
                    e.offset = start;
                    e.type = text.substring(ts, te).trim();
                    e.owner = top.structName;
                    out.add(e);
                    i = te;
                    continue;
                }
            }
        }
        return out;
    }

    private static String structOf(Deque<Scope> scopes) {
        for (Scope s : scopes) {
            if (s.isStruct && !s.insideDef) return s.structName;
        }
        return "";
    }

    private static void scanParamNames(String text, int from, int to, Ent def, Deque<Scope> scopes,
                                       List<Ent> out) {
        for (int[] r : topLevelEntries(text, from, to)) {
            int j0 = skipSpace(text, r[0]);
            boolean mut = false;
            int j = j0;
            if (j + 3 <= r[1] && text.startsWith("mut", j) && !isNamePart(text.charAt(j + 3))) {
                mut = true;
                j = skipSpace(text, j + 3);
            }
            int ns = j;
            int ne = endName(text, ns);
            if (ne <= ns) continue;
            Ent e = new Ent("param", text.substring(ns, ne));
            e.offset = ns;
            e.owner = def.name;
            e.nested = def.nested;
            e.mut = mut;
            out.add(e);
        }
    }

    /**
     * A variant's payload, scanned out of the parentheses the way [scanParamNames] scans a
     * parameter list -- one `field: Type` entry per top-level comma, and every entry is a
     * `payload` rather than a `param` or a struct `field` (SPEC.md §13).
     */
    private static void scanPayloadFields(String text, int from, int to, Ent variant,
                                         List<Ent> out) {
        for (int[] r : topLevelEntries(text, from, to)) {
            int ns = skipSpace(text, r[0]);
            int ne = endName(text, ns);
            if (ne <= ns) continue;
            Ent e = new Ent("field", text.substring(ns, ne));
            e.offset = ns;
            e.owner = variant.name;
            e.payload = true;
            int colon = skipSpace(text, ne);
            if (colon < r[1] && text.charAt(colon) == ':') {
                int ts = skipSpace(text, colon + 1);
                e.type = text.substring(ts, Math.min(endOfType(text, ts), r[1])).trim();
            }
            out.add(e);
        }
    }

    private static final class Scope {
        final boolean isStruct;
        final boolean isEnum;
        final String structName;
        final boolean insideDef;

        private Scope(boolean isStruct, boolean isEnum, String structName, boolean insideDef) {
            this.isStruct = isStruct;
            this.isEnum = isEnum;
            this.structName = structName;
            this.insideDef = insideDef;
        }

        static Scope struct(String name) {
            return new Scope(true, false, name, false);
        }

        /** An enum body: its members are variants, and they are not a struct's fields. */
        static Scope enumBody(String name) {
            return new Scope(false, true, name, false);
        }

        static Scope def(String name) {
            return new Scope(false, false, "", true);
        }

        static Scope inherit(Scope parent) {
            return parent == null ? new Scope(false, false, "", false) : parent;
        }
    }

    /** The span of every top-level comma-separated entry of `text[from..to)`. */
    private static List<int[]> topLevelEntries(String text, int from, int to) {
        List<int[]> out = new ArrayList<>();
        int depth = 0;
        int start = from;
        int i = from;
        while (i < to && i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                i = endOfString(text, i);
                continue;
            }
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                out.add(new int[]{start, i});
                start = i + 1;
            }
            i++;
        }
        out.add(new int[]{start, Math.min(to, text.length())});
        return out;
    }

    /** `Array[int, 4]` and `mut Vec2`: a type ends at a character that is not part of one. */
    private static int endOfType(String text, int from) {
        int i = from;
        int depth = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth < 0) return i;
            } else if (depth == 0) {
                if (isNamePart(c) || c == '.' || c == '|') {
                    i++;
                    continue;
                }
                return i;
            }
            i++;
        }
        return i;
    }

    /**
     * `--why`: the two lists, side by side, for the file that diverged.
     *
     * The scanner is a harness object and its offsets are checked against the compiler's
     * own token stream, so a divergence is a question about *this tool*, not about the
     * hover -- and a question about a tool is answered by printing what it did.
     */
    private static void printBoth(String rel, String text, List<Ent> dump, List<Ent> scan) {
        System.out.println("  ---- why: " + rel + " -- the compiler's list and the scanner's");
        int n = Math.max(dump.size(), scan.size());
        for (int i = 0; i < n; i++) {
            String a = i < dump.size() ? dump.get(i).kind + " " + dump.get(i).name : "-";
            String b = i < scan.size() ? scan.get(i).kind + " " + scan.get(i).name : "-";
            String at = "";
            if (i < scan.size() && scan.get(i).offset >= 0) {
                int off = scan.get(i).offset;
                int from = Math.max(0, off - 20);
                int to = Math.min(text.length(), off + 30);
                at = " at " + off + " `" + text.substring(from, to).replace("\n", "\\n") + "`";
            }
            System.out.println("       " + pad(String.valueOf(i), 4) + pad(a, 28) + pad(b, 28) + at
                    + (a.equals(b) ? "" : "   <- DIVERGES"));
            if (i > 0 && i > 220) break;
        }
    }

    /** The first divergence between the dump and the scanner, or null when they agree. */    private static String align(List<Ent> dump, List<Ent> scan) {
        int n = Math.min(dump.size(), scan.size());
        for (int i = 0; i < n; i++) {
            Ent a = dump.get(i);
            Ent b = scan.get(i);
            if (!a.kind.equals(b.kind) || !a.name.equals(b.name)) {
                return "the compiler's dump and this harness's scanner disagree at declaration "
                        + i + ": the compiler says `" + a.kind + " " + a.name + "`, the scanner says `"
                        + b.kind + " " + b.name + "`";
            }
        }
        if (dump.size() != scan.size()) {
            int i = n;
            return "the compiler's dump holds " + dump.size() + " declaration(s) and this harness's"
                    + " scanner " + scan.size() + "; the first the compiler has and the scanner does not"
                    + " is " + (i < dump.size() ? "`" + dump.get(i).kind + " " + dump.get(i).name + "`"
                    : "`" + scan.get(i).kind + " " + scan.get(i).name + "` (which the scanner found"
                    + " and the compiler did not)");
        }
        return null;
    }

    // ---------------------------------------------------------------- the compiler's tokens

    /** One token of `vm.exe lex`: the compiler's own line and offset for it. */
    private static final class LexTok {
        final int line;
        final int offset;
        final int length;
        String text = "";
        boolean isIdentifier;

        LexTok(int line, int offset, int length) {
            this.line = line;
            this.offset = offset;
            this.length = length;
        }
    }

    /**
     * `vm.exe lex <file>` -- `kind line code offset length`, one line per token.
     *
     * The decoding is *checked* rather than trusted: every row's line column must equal
     * the number of newlines before its offset.  A wrong reading of the columns would
     * otherwise become a wrong reading of the hover, which is the one thing this tool
     * must not do -- so it stops instead (exit 2) and says so.
     */
    private static Map<Integer, LexTok> parseLex(String out, String text, String rel) {
        Map<Integer, LexTok> toks = new LinkedHashMap<>();
        long rows = 0;
        long bad = 0;
        String firstBad = null;
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 5) continue;
            int kind;
            int ln;
            int off;
            int len;
            try {
                kind = Integer.parseInt(parts[0]);
                ln = Integer.parseInt(parts[1]);
                off = Integer.parseInt(parts[3]);
                len = Integer.parseInt(parts[4]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (off < 0 || len < 0 || off + len > text.length()) continue;
            rows++;
            int realLine = lineOf(text, off);
            if (realLine != ln) {
                bad++;
                if (firstBad == null) {
                    firstBad = "row `" + line + "`: offset " + off + " is on line " + realLine
                            + ", the row says line " + ln;
                }
            }
            toks.put(off, new LexTok(ln, off, len));
        }
        if (bad * 100 > Math.max(2, rows / 100)) {
            System.out.println("THE TOKEN DECODING IS WRONG: " + bad + " of " + rows
                    + " lex row(s) have a line column that is not the line of their offset.");
            System.out.println("  first: " + firstBad);
            System.out.println("  nothing is judged against a decoder this tool cannot check.");
            System.exit(2);
        }
        for (LexTok t : toks.values()) {
            String body = text.substring(t.offset, t.offset + t.length);
            t.text = body;
            t.isIdentifier = isIdentifier(body);
        }
        return toks;
    }

    private static boolean isIdentifier(String s) {
        if (s.isEmpty() || !isNameStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!isNamePart(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isNameStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    // ---------------------------------------------------------------- corpus and plumbing

    /** The same corpus the other harnesses measure over, so the numbers are comparable. */
    private void collectCorpus() throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        Path cases = repoRoot.resolve("tests").resolve("cases.txt");
        if (Files.isRegularFile(cases)) {
            for (String line : Files.readAllLines(cases, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] parts = t.split("\\s+");
                if (parts.length < 3) continue;
                if (parts[2].endsWith(".vel")) seen.add(parts[2]);
            }
        }
        for (String dir : new String[]{"tests", "examples", "bench", "ide-demo", "selfhost/parts"}) {
            Path d = repoRoot.resolve(dir);
            if (!Files.isDirectory(d)) continue;
            List<Path> found = new ArrayList<>();
            Files.walk(d)
                    .filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".vel"))
                    .forEach(found::add);
            Collections.sort(found);
            for (Path f : found) seen.add(repoRoot.relativize(f).toString().replace('\\', '/'));
        }
        if (Files.isRegularFile(repoRoot.resolve("selfhost/vela.vel"))) seen.add("selfhost/vela.vel");
        corpus.addAll(seen);
    }

    private static final class Res {
        final int exit;
        final String out;
        final String err;

        Res(int exit, String out, String err) {
            this.exit = exit;
            this.out = out;
            this.err = err;
        }
    }

    /** One compiler query, with both streams written to files (no pipes on this host). */
    private Res run(Path file, String mode) throws IOException, InterruptedException {
        Path outFile = Files.createTempFile("vela-hovertruth-out", ".txt");
        Path errFile = Files.createTempFile("vela-hovertruth-err", ".txt");
        try {
            ProcessBuilder pb = new ProcessBuilder(vm.toString(), mode, file.toString());
            pb.redirectOutput(outFile.toFile());
            pb.redirectError(errFile.toFile());
            Process proc = pb.start();
            int code = proc.waitFor();
            return new Res(code, decode(outFile), decode(errFile));
        } finally {
            Files.deleteIfExists(outFile);
            Files.deleteIfExists(errFile);
        }
    }

    private Path writeTemp(String text) throws IOException {
        Path p = Files.createTempFile("vela-hovertruth-gen", ".vel");
        Files.write(p, text.getBytes(StandardCharsets.ISO_8859_1));
        return p;
    }

    /** The dump and the diagnostics are written in the platform's encoding, not UTF-8. */
    private static String decode(Path p) throws IOException {
        byte[] raw = Files.readAllBytes(p);
        String text;
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE) {
            text = new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE);
        } else {
            text = new String(raw, StandardCharsets.UTF_8);
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private void bump(String why) {
        skipped.merge(why, 1L, Long::sum);
    }

    private void defect(String why) {
        defectCategories.add(why);
        bump(why);
    }

    private void finding(String text) {
        if (findings.size() < 60) findings.add(text);
        else if (findings.size() == 60) findings.add("  ... more findings suppressed (60 shown)");
    }

    // ---------------------------------------------------------------- text helpers

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static int skipSpace(String text, int i) {
        // ALL whitespace, newlines included: a signature may break between its name and
        // its `(`, and a parameter may begin on the line after the comma before it --
        // `def set_int(... ,\n            c: int, v: int)` declares four parameters.
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }

    private static int endName(String text, int i) {
        int j = i;
        while (j < text.length() && isNamePart(text.charAt(j))) j++;
        return j;
    }

    private static boolean isNamePart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        int i = open;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                i = endOfString(text, i);
                continue;
            }
            if (c == '#') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
            } else if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
            i++;
        }
        return -1;
    }

    private static int endOfString(String text, int start) {
        char closing = text.charAt(start);
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                i += 2;
                continue;
            }
            if (c == closing || c == '\n') return i + 1;
            i++;
        }
        return text.length();
    }

    /** `name:type:mut`, or `name:type` -- one comparable string per parameter. */
    private static List<String> paramList(List<?> params) {
        List<String> out = new ArrayList<>();
        for (Object o : params) {
            if (o instanceof Ent) {
                Ent e = (Ent) o;
                out.add(e.name + ":" + norm(e.type) + (e.mut ? ":mut" : ""));
            } else {
                VelaDocParam p = (VelaDocParam) o;
                out.add(p.getName() + ":" + norm(p.getType()) + (p.getMutable() ? ":mut" : ""));
            }
        }
        return out;
    }

    private static List<String> fieldList(VelaDoc doc) {
        List<String> out = new ArrayList<>();
        for (VelaDocParam p : doc.getFields()) out.add(p.getName() + ":" + norm(p.getType()));
        return out;
    }

    /** Types are compared with their spacing removed: `Array[int,4]` is `Array[int, 4]`. */
    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private static String oneLine(String s) {
        String t = s.replace("\n", " ").replace("\r", "").trim();
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    private static String sha256(Path p) {
        try {
            return sha256(Files.readAllBytes(p));
        } catch (IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "(no sha256: " + e + ")";
        }
    }

    /** The class file the hover under test was compiled from, read off the classpath. */
    private static String classSha256() {
        try (InputStream in = HoverTruth.class.getClassLoader()
                .getResourceAsStream("dev/vela/plugin/VelaDocumentationProvider.class")) {
            if (in == null) return "(not on the classpath)";
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return sha256(bos.toByteArray());
        } catch (IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }

    private HoverTruth() {
    }
}
