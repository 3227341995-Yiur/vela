package dev.vela.plugin

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile

/**
 * Local inspections whose *judgement is the compiler's*.
 *
 * WHY THESE THREE RULES AND NOT THIRTY
 *
 * The plugin's one rule is that `vm.exe` decides what a Vela program means, and
 * everything else in this plugin is a way of asking it.  An inspection is the one
 * feature where that rule can be broken silently, because an inspection states a
 * rule in its own code: a rule the compiler does not have teaches the user to
 * write a program the compiler will not accept, and a quick fix that does not
 * repair anything teaches the user to trust a broken button.  So the three rules
 * here are chosen on one criterion, and it is not "useful" 閳?it is **measurable**:
 *
 *   * the refusal the inspection reports is a `vm.exe check` refusal that the
 *     language's own test battery already asserts, with the exact message
 *     ([VelaInspectionRules.authority] holds the substring `check` prints), and
 *   * the repair the fix writes makes `check` exit 0 on that file.
 *
 * Both halves are run by `tools/harness/src/InspectionProbe.java` over the corpus,
 * which is why the detection lives in a pure object ([VelaInspectionRules]) over
 * `VelaSyntaxParser`'s tree: the class the platform calls below is a ten-line
 * adapter, and the harness measures the same code path the editor will.
 *
 * The three, and the compiler line each one claims:
 *
 *   VelaImmutableAssignment   `vela: safety error: cannot assign to 'x': it was
 *                             declared immutable`   -> write `mut` at the declaration
 *   VelaStringConcatenation   `vela: type error: string concatenation is not
 *                             implemented in Vela 0.1`   -> `concat(a, b)`
 *   VelaIntFloatMixing        `vela: type error: operator '+' mixes int and float`
 *                             -> `to_float(...)` on the int operand
 *
 * WHAT IS DELIBERATELY *NOT* REPORTED, AND WHY THAT IS NOT AN OVERSIGHT
 *
 * Every rule fires only where its fix is known to work, so each rule has a
 * boundary that is a measured fact rather than caution for its own sake:
 *
 *   * an assignment to an immutable *parameter* is refused by the compiler with
 *     the same words, but the repair is not the same word: `mut n: int` on a
 *     scalar parameter satisfies `check` while changing nothing (the argument is
 *     a copy 閳?`tests/safety/cases/hole_mut_scalar_parameter.vel`), so a fix that
 *     wrote `mut` there would be a no-op the plugin must not teach.  Parameters
 *     are therefore out of scope for that rule;
 *   * an assignment to an immutable *array* is refused with the same words too,
 *     but `mut` does not repair it 閳?`vela: safety error: cannot rebind array
 *     'a'` is what comes next (`tests/safety/cases/scope_array_rebind_refused.vel`
 *     is the legal twin's other half).  Array-typed declarations are skipped;
 *   * `'%'` and `'**'` are left alone: the compiler words their mismatches as
 *     `'%' is integer remainder; got int and float` and `'**' needs matching
 *     numeric operands`, and whether the int or the float side is the one to
 *     convert is not written in the message.  Two rules with an ambiguous fix
 *     are worse than one honest miss.
 */

/** One textual edit: replace `[start, end)` with [replacement]. */
class VelaInspectionEdit(
    @JvmField val start: Int,
    @JvmField val end: Int,
    @JvmField val replacement: String,
) {
    override fun toString(): String = "[$start,$end)->\"$replacement\""
}

/**
 * One finding: where it is, what to say about it, and the edits that repair it.
 *
 * [line] is 1-based and computed the way the compiler counts lines, because the
 * whole point of the pair (this finding, one `vm.exe check` diagnostic) is that
 * the two can be compared.
 */
class VelaInspectionFinding(
    /** The rule id, which is also the `LocalInspectionTool.getShortName()`. */
    @JvmField val rule: String,
    @JvmField val line: Int,
    /** The character range the squiggle covers, in the file. */
    @JvmField val start: Int,
    @JvmField val end: Int,
    @JvmField val message: String,
    /** The name the rule is about, `""` when it is about an expression. */
    @JvmField val subject: String,
    /** What the fix says it does, i.e. `LocalQuickFix.getName()`. */
    @JvmField val fixName: String,
    @JvmField val edits: List<VelaInspectionEdit>,
    /**
     * The buffer the finding was computed from.
     *
     * Carried so a *postponed* fix can tell "the document is the one I analysed"
     * from "the user has typed since" 閳?see [VelaInspectionFix].  One `String`
     * instance per parse, shared by every finding of that parse, so this costs a
     * reference rather than a copy.
     */
    @JvmField val source: String,
) {
    override fun toString(): String = "$rule:$line $message"
}

/**
 * The rules, as pure functions of a buffer.
 *
 * No PsiFile, no project, no Application: the input is the file's text and the
 * output is a list of findings, which is what makes them measurable headlessly and
 * what keeps the IDE-side classes below to registration and drawing.
 *
 * The tree is [VelaSyntaxParser]'s, not a regular expression over the text: a
 * `+` inside a string literal, a `+` in a comment and a unary `+` are different
 * nodes, and only the tree can say which is which.  When the parser reports a
 * problem the rules have **no opinion at all** 閳?a file that is not valid Vela is
 * the parser's message to give, and guessing at a half-parsed tree is how an
 * inspection invents a second, weaker compiler.
 */
object VelaInspectionRules {

    @JvmField val IMMUTABLE_ASSIGNMENT = "VelaImmutableAssignment"
    @JvmField val STRING_CONCATENATION = "VelaStringConcatenation"
    @JvmField val INT_FLOAT_MIXING = "VelaIntFloatMixing"

    /** In the order the report prints them; the harness also iterates this. */
    @JvmField val RULES: List<String> = listOf(
        IMMUTABLE_ASSIGNMENT, STRING_CONCATENATION, INT_FLOAT_MIXING,
    )

    /**
     * The substring `vm.exe check` prints on the finding's own line, per rule.
     *
     * These are not descriptions of the rules; they are quotes from the compiler,
     * and `InspectionProbe` fails a rule whose findings do not come back with one
     * of them.  Every one was read out of a real run over
     * `tests/safety/cases/`, which is where the battery already asserts them.
     */
    private val AUTHORITY: Map<String, List<String>> = mapOf(
        IMMUTABLE_ASSIGNMENT to listOf(
            "it was declared immutable", // `cannot assign to 'x'` / `cannot modify 'x'`
        ),
        STRING_CONCATENATION to listOf(
            "string concatenation is not implemented",
        ),
        INT_FLOAT_MIXING to listOf(
            // Both source orders: `1 + 2.5` is "mixes int and float", `2.5 + 1` is
            // "mixes float and int" (measured over tests/safety/cases and
            // tests/build/check_cases/implicit_int_to_float.vel), and `1 / 2.5` is
            // "'/' is float division; got int and float".
            "int and float",
            "float and int",
        ),
    )

    /** The compiler substrings this rule's findings must come back with. */
    @JvmStatic
    fun authority(rule: String): List<String> = AUTHORITY[rule] ?: emptyList()

    /**
     * Every finding of every rule, ordered by position.
     *
     * One parse, three rules: the inspections share the tree because building it is
     * the only cost here and the platform asks all three of them for the same file
     * within one pass.
     */
    @JvmStatic
    fun findAll(text: CharSequence): List<VelaInspectionFinding> {
        val findings = ArrayList<VelaInspectionFinding>()
        for (rule in RULES) findings.addAll(find(rule, text))
        findings.sortWith(compareBy({ it.start }, { it.end }, { it.rule }))
        return findings
    }

    /**
     * The findings of one rule.
     *
     * A rule that is not in [RULES] answers nothing rather than throwing: the
     * harness asks by name, and an unknown name must be a zero and not a crash.
     */
    @JvmStatic
    fun find(rule: String, text: CharSequence): List<VelaInspectionFinding> {
        if (rule !in RULES) return emptyList()
        val src = text.toString()
        val tree = VelaSyntaxParser.parse(src)
        if (tree.problems.isNotEmpty()) return emptyList()
        val rules = VelaInspectionWalk(src, tree)
        return when (rule) {
            IMMUTABLE_ASSIGNMENT -> rules.immutableAssignments()
            STRING_CONCATENATION -> rules.stringConcatenations()
            INT_FLOAT_MIXING -> rules.intFloatMixing()
            else -> emptyList()
        }
    }

    /**
     * Apply a finding's edits to the text.
     *
     * Two properties a fix has to have to be worth offering, both of them about
     * *sets* of edits rather than one:
     *
     *   * **identical edits collapse.** One immutable binding assigned twice is two
     *     findings whose repair is the same single `mut ` in the same place; writing
     *     it twice would produce `mut mut x`. A user who applies both fixes must get
     *     what a user who applies one gets.
     *   * **overlapping edits are refused, not guessed.** The rules are written so
     *     that they cannot produce an overlap (each edit covers a distinct
     *     operand/declaration), so an overlap is a bug in a rule; dropping the edit
     *     that overlaps leaves the text a program instead of a paste accident.
     *
     * `apply` returns the text; [appliedTo] reports how many edits landed and how
     * many were refused, so a caller can tell "nothing to do" from "the rules
     * disagreed with each other".
     */
    @JvmStatic
    fun apply(text: String, findings: List<VelaInspectionFinding>): String =
        appliedTo(text, findings).text

    /** The text, plus what it took to produce it. */
    class Applied(
        @JvmField val text: String,
        @JvmField val edits: Int,
        @JvmField val duplicates: Int,
        @JvmField val overlaps: Int,
    )

    @JvmStatic
    fun appliedTo(text: String, findings: List<VelaInspectionFinding>): Applied {
        val unique = LinkedHashSet<VelaInspectionEdit>()
        var duplicates = 0
        for (f in findings) {
            for (e in f.edits) {
                if (!unique.add(e)) duplicates++
            }
        }
        val ordered = unique.sortedWith(compareByDescending<VelaInspectionEdit> { it.start }
            .thenByDescending { it.end })
        val out = StringBuilder(text)
        var applied = 0
        var overlaps = 0
        var lastStart = Int.MAX_VALUE
        for (e in ordered) {
            if (e.start < 0 || e.end > out.length || e.start > e.end) {
                overlaps++
                continue
            }
            // Edits never share a character: `e.end <= lastStart` is the test, and
            // the ordered list makes the previous (rightmost) edit's start the mark.
            // `e.end <= lastStart` is the non-overlap test; `lastStart` is the start of
            // the edit to the right of this one, and an insert (start == end) at exactly
            // that offset is still a collision and still refused.
            if (e.end > lastStart) {
                overlaps++
                continue
            }
            out.replace(e.start, e.end, e.replacement)
            applied++
            lastStart = e.start
        }
        return Applied(out.toString(), applied, duplicates, overlaps)
    }

    /** The line a character offset is on, 1-based, counted the way the compiler counts. */
    @JvmStatic
    fun lineOf(text: String, offset: Int): Int {
        var line = 1
        val stop = offset.coerceIn(0, text.length)
        for (i in 0 until stop) if (text[i] == '\n') line++
        return line
    }
}

/**
 * One pass over one tree, shared by the three rules.
 *
 * The index is deliberately small and dumb: names, where they are declared, and
 * the type written beside the declaration.  It is not a scope model 閳?the plugin
 * has none, on purpose (see VelaModel) 閳?and every rule below is written so that a
 * name it cannot resolve produces **no finding** rather than a guess.  That is the
 * asymmetry that keeps a false alarm impossible while a miss only costs a squiggle.
 */
private class VelaInspectionWalk(
    private val src: String,
    private val tree: VelaSyntaxTree,
) {

    /** Declarations by name: `DECL`, `PARAM`, and `FOR`'s loop variable. */
    private val declarations = HashMap<String, MutableList<VelaSyntaxNode>>()

    /** Names this file gives to a function, method or struct. */
    private val callables = HashSet<String>()

    /** Every node, so the rules do not each walk the tree. */
    private val all = ArrayList<VelaSyntaxNode>(256)

    init {
        collect(tree.root)
    }

    private fun collect(n: VelaSyntaxNode) {
        all.add(n)
        when (n.kind) {
            VelaNodeKind.DECL, VelaNodeKind.PARAM -> declarations.getOrPut(n.name) { ArrayList(1) }.add(n)
            VelaNodeKind.FOR -> declarations.getOrPut(n.name) { ArrayList(1) }.add(n)
            VelaNodeKind.DEF, VelaNodeKind.STRUCT -> if (n.name.isNotEmpty()) callables.add(n.name)
            else -> {}
        }
        for (c in n.children) collect(c)
    }

    // ------------------------------------------------------------ coordinates

    /**
     * A node's first character.
     *
     * The token ranges are the parser's own bookkeeping and one token kind does not
     * cover what the source shows: a `STRING` token spans the literal's *contents*,
     * so the quotes around it are one character outside the token on each side.  A
     * replacement that did not know this would write `concat(a", "b)`.
     */
    private fun charStart(n: VelaSyntaxNode): Int {
        if (n.charStart >= 0) return n.charStart
        val t = tok(n.startTok) ?: return -1
        return if (n.kind == VelaNodeKind.STR) t.start - 1 else t.start
    }

    private fun charEnd(n: VelaSyntaxNode): Int {
        if (n.charEnd >= 0) return n.charEnd
        val t = tok(n.endTok) ?: return -1
        return if (n.kind == VelaNodeKind.STR) t.end + 1 else t.end
    }

    private fun tok(i: Int): VelaTok? = if (i in tree.toks.indices) tree.toks[i] else null

    private fun textOf(n: VelaSyntaxNode): String {
        val s = charStart(n)
        val e = charEnd(n)
        if (s < 0 || e > src.length || s > e) return ""
        return src.substring(s, e)
    }

    /**
     * The operator between a binary node's two operands.
     *
     * NOT `n.startTok`: `VelaSyntaxNode.add` pulls a node's start back to its
     * leftmost child, so a `binop` begins where its left operand begins and its
     * `startTok` is that operand's, not the operator's.  The operator is the first
     * token after the left operand that is an operator with this node's own code 閳?     * a search rather than a `+ 1` because a comment or a line break may sit
     * between them, and a squiggle drawn on a comment is a wrong line.
     */
    private fun operatorRange(n: VelaSyntaxNode): Pair<Int, Int>? {
        if (n.children.size != 2) return null
        val left = n.children[0]
        val right = n.children[1]
        if (left.endTok < 0) return null
        var i = left.endTok + 1
        val stop = if (right.startTok > 0) right.startTok else tree.toks.size
        while (i < stop && i < tree.toks.size) {
            val t = tree.toks[i]
            if (t.kind == VelaTokKind.OP && t.code == n.op) return t.start to t.end
            i++
        }
        return null
    }

    private fun binops(): List<VelaSyntaxNode> =
        all.filter { (it.kind == VelaNodeKind.BINOP || it.kind == VelaNodeKind.CMP) && it.children.size == 2 }

    // ------------------------------------------------------------- the typing

    /**
     * `int`, `float`, or null for "this file does not say".
     *
     * Null is the common answer and that is the design: a call's return type, a
     * field's type and an array element's type are all null here, so nothing is
     * reported about them.  What is left is exactly the arithmetic whose types are
     * visible in the characters 閳?literals, names whose declarations agree, and the
     * three arithmetic operators that return their operand's own type.
     */
    private fun numericType(n: VelaSyntaxNode): String? = when (n.kind) {
        VelaNodeKind.INT -> "int"
        VelaNodeKind.FLOAT -> "float"
        VelaNodeKind.NAME -> declaredType(n.name)
        VelaNodeKind.UNARY -> {
            // `-1` is an int and `-2.5` is a float; the compiler types them the same
            // way (`tests/safety/cases/strict_no_unary_neg_str.vel` and its twins).
            if (n.op != VelaOps.MINUS && n.op != VelaOps.PLUS) null
            else n.children.firstOrNull()?.let { numericType(it) }
        }
        VelaNodeKind.BINOP -> {
            if (n.op != VelaOps.PLUS && n.op != VelaOps.MINUS && n.op != VelaOps.STAR && n.op != VelaOps.SLASH) {
                null
            } else {
                val l = n.children.getOrNull(0)?.let { numericType(it) }
                val r = n.children.getOrNull(1)?.let { numericType(it) }
                if (l != null && l == r) l else null
            }
        }
        else -> null
    }

    /** The type written at a name's declaration(s), when every one of them agrees. */
    private fun declaredType(name: String): String? {
        if (name.isEmpty()) return null
        val decls = declarations[name] ?: return null
        var seen: String? = null
        for (d in decls) {
            val t = when (d.kind) {
                VelaNodeKind.FOR -> "int" // `range` yields ints, and nothing else loops here
                else -> d.typeText.removePrefix("mut ").trim()
            }
            if (t != "int" && t != "float") return null
            if (seen == null) seen = t else if (seen != t) return null
        }
        return seen
    }

    private fun isStringOperand(n: VelaSyntaxNode): Boolean = when (n.kind) {
        VelaNodeKind.STR -> true
        VelaNodeKind.NAME -> declaredString(n.name)
        VelaNodeKind.CALL -> {
            val callee = n.children.firstOrNull()
            callee != null && callee.kind == VelaNodeKind.NAME && callee.name == "concat"
        }
        else -> false
    }

    private fun declaredString(name: String): Boolean {
        if (name.isEmpty()) return false
        val decls = declarations[name] ?: return false
        return decls.isNotEmpty() && decls.all {
            it.kind != VelaNodeKind.FOR && it.typeText.removePrefix("mut ").trim() == "str"
        }
    }

    // -------------------------------------------------------------- rule one

    /**
     * `x = 2` where `x` was declared `x: int = 1`: the compiler refuses it with
     * `cannot assign to 'x': it was declared immutable`, and the repair is the word
     * the declaration is missing.
     */
    fun immutableAssignments(): List<VelaInspectionFinding> {
        val out = ArrayList<VelaInspectionFinding>()
        for (n in all) {
            if (n.kind != VelaNodeKind.ASSIGN && n.kind != VelaNodeKind.AUGASSIGN) continue
            val target = n.children.firstOrNull() ?: continue
            // `a.b = v` and `a[i] = v` are not this rule: the name is not the thing
            // being assigned, and the declaration that would take the `mut` is the
            // *receiver's*, one level out.
            if (target.kind != VelaNodeKind.NAME) continue
            val name = target.name
            if (name.isEmpty()) continue
            // A parameter, or a name this file also gives to a function or struct:
            // which declaration the assignment binds is not something this model can
            // say, and a fix for the wrong one rewrites an unrelated line.
            if (declarations[name]?.any { it.kind == VelaNodeKind.PARAM } == true) continue
            if (name in callables) continue
            val decls = declarations[name] ?: continue
            // Every declaration of that name must be a mutable-able local, and none of
            // them an array: `mut` on an array turns "cannot assign ... immutable" into
            // "cannot rebind array 'a'", which is not a repair.
            if (decls.any { it.kind != VelaNodeKind.DECL }) continue
            if (decls.any { (it.flags and 1) != 0 }) continue // already mutable somewhere
            if (decls.any { it.typeText.trimStart().startsWith("Array") }) continue
            val decl = decls.minByOrNull { charStart(it) } ?: continue
            val declAt = charStart(decl)
            val targetAt = charStart(target)
            if (declAt < 0 || targetAt < 0 || declAt >= targetAt) continue // declared after use: not this rule
            val verb = if (n.kind == VelaNodeKind.AUGASSIGN) "cannot modify" else "cannot assign to"
            val said = "$verb '$name': it was declared immutable"
            out.add(
                VelaInspectionFinding(
                    rule = VelaInspectionRules.IMMUTABLE_ASSIGNMENT,
                    line = VelaInspectionRules.lineOf(src, targetAt),
                    start = targetAt,
                    end = charEnd(target),
                    message = "Vela refuses this assignment: `$name` was declared immutable, and " +
                        "the compiler says \"$said\"",
                    subject = name,
                    fixName = "Add 'mut' to the declaration of '$name'",
                    edits = listOf(VelaInspectionEdit(declAt, declAt, "mut ")),
                    source = src,
                )
            )
        }
        return out
    }

    // -------------------------------------------------------------- rule two

    /**
     * `"a" + "b"`: `vela: type error: string concatenation is not implemented in
     * Vela 0.1`.  The language's only way to join two strings is the named builtin,
     * so the fix writes `concat(a, b)`.
     *
     * Only the *outermost* `+` of a chain is reported, and its fix rewrites the
     * whole chain 閳?`"a" + "b" + "c"` becomes `concat(concat("a", "b"), "c")`.  A
     * fix on the inner `+` alone would leave a `+` behind and the file would still
     * be refused, which is the one thing a quick fix may not do.
     */
    fun stringConcatenations(): List<VelaInspectionFinding> {
        val out = ArrayList<VelaInspectionFinding>()
        for (n in all) {
            if (!isConcatChain(n)) continue
            if (isConcatChain(parentOf(n) ?: NO_NODE)) continue // report the outermost only
            val range = operatorRange(n) ?: continue
            val left = n.children[0]
            val right = n.children[1]
            val at = charStart(left)
            val to = charEnd(right)
            if (at < 0 || to > src.length || at >= to) continue
            out.add(
                VelaInspectionFinding(
                    rule = VelaInspectionRules.STRING_CONCATENATION,
                    line = VelaInspectionRules.lineOf(src, range.first),
                    start = range.first,
                    end = range.second,
                    message = "Vela has no `+` for strings: the compiler refuses \"string " +
                        "concatenation is not implemented in Vela 0.1\"",
                    subject = "",
                    fixName = "Join with concat(...)",
                    edits = listOf(VelaInspectionEdit(at, to, rewriteConcat(n))),
                    source = src,
                )
            )
        }
        return out
    }

    /** `a + b` where both sides are strings (or a string `+` chain). */
    private fun isConcatChain(n: VelaSyntaxNode): Boolean {
        if (n.kind != VelaNodeKind.BINOP || n.op != VelaOps.PLUS || n.children.size != 2) return false
        return isStringOperand(n.children[0]) && isStringOperand(n.children[1])
    }

    private fun rewriteConcat(n: VelaSyntaxNode): String {
        if (!isConcatChain(n)) return textOf(n)
        return "concat(" + rewriteConcat(n.children[0]) + ", " + rewriteConcat(n.children[1]) + ")"
    }

    // ------------------------------------------------------------ rule three

    /** The operators whose mixed operands the compiler refuses, and that `to_float` repairs. */
    private val mixingOps = setOf(
        VelaOps.PLUS, VelaOps.MINUS, VelaOps.STAR, VelaOps.SLASH,
        VelaOps.LT, VelaOps.GT, VelaOps.LE, VelaOps.GE, VelaOps.EQEQ, VelaOps.NE,
    )

    /**
     * `1 + 2.5`: `vela: type error: operator '+' mixes int and float`, exit 2 閳?Vela
     * has no implicit conversion, and `tests/safety/cases/strict_no_implicit_conversion.vel`
     * is the program that says so.  The repair is the explicit `to_float(1)` that
     * `strict_to_float_explicit.vel` is the passing twin of.
     *
     * The `int` side is converted, never the `float` side: `to_int(2.5)` would be a
     * *different* program (it truncates), and a fix may only write the program the
     * user wrote, legally.
     */
    fun intFloatMixing(): List<VelaInspectionFinding> {
        val out = ArrayList<VelaInspectionFinding>()
        for (n in binops()) {
            if (n.op !in mixingOps) continue
            val left = n.children[0]
            val right = n.children[1]
            val l = numericType(left)
            val r = numericType(right)
            val intSide = when {
                l == "int" && r == "float" -> left
                l == "float" && r == "int" -> right
                else -> null
            } ?: continue
            val range = operatorRange(n) ?: continue
            val at = charStart(intSide)
            val to = charEnd(intSide)
            if (at < 0 || to > src.length || at >= to) continue
            val op = velaOpName(n.op)
            out.add(
                VelaInspectionFinding(
                    rule = VelaInspectionRules.INT_FLOAT_MIXING,
                    line = VelaInspectionRules.lineOf(src, range.first),
                    start = range.first,
                    end = range.second,
                    message = "Vela has no implicit int/float conversion: the compiler refuses " +
                        "\"operator '$op' mixes int and float\"",
                    subject = "",
                    fixName = "Convert the int operand with to_float(...)",
                    edits = listOf(VelaInspectionEdit(at, to, "to_float(" + textOf(intSide) + ")")),
                    source = src,
                )
            )
        }
        return out
    }

    // ------------------------------------------------------------- the parents

    /**
     * Parent links, built from the parsed tree rather than stored on it.
     *
     * `VelaSyntaxNode` has no parent pointer 閳?the platform supplies parentage
     * through the PSI, and a second copy of it inside the model would be a second
     * thing that can disagree with the tree.  A `HashMap` over one walk is cheaper
     * than that, and it is thrown away with the pass.
     */
    private val parents = HashMap<VelaSyntaxNode, VelaSyntaxNode>()

    private fun parentOf(n: VelaSyntaxNode): VelaSyntaxNode? {
        if (parents.isEmpty()) {
            parents[tree.root] = NO_NODE
            for (node in all) for (c in node.children) parents[c] = node
        }
        val p = parents[n]
        return if (p == null || p === NO_NODE) null else p
    }

    private companion object {
        val NO_NODE = VelaSyntaxNode(VelaNodeKind.ERROR, -1, -1)
    }
}

// ---------------------------------------------------------------------------
// the IDE side: registration, one visitor, three tools

/**
 * What the platform calls, for all three rules.
 *
 * The visitor asks [VelaInspectionRules] about `file.text` and registers what comes
 * back on the file element with an absolute [TextRange].  Nothing decides anything
 * here, which is the point: the harness runs *this* visitor (with the platform's
 * own `ProblemsHolder`) and the identical call path the editor takes.
 */
abstract class VelaInspectionBase(
    private val rule: String,
    private val title: String,
    private val description: String,
) : LocalInspectionTool() {

    override fun getShortName(): String = rule

    override fun getDisplayName(): String = title

    override fun getGroupDisplayName(): String = "Vela"

    override fun getStaticDescription(): String = description

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                val text = file.text
                for (f in VelaInspectionRules.find(rule, text)) {
                    holder.registerProblem(
                        file,
                        TextRange(f.start, f.end),
                        f.message,
                        VelaInspectionFix(f),
                    )
                }
            }
        }
}

/**
 * `x = 2` with `x: int = 1` above it.
 *
 * The compiler's own words for this are `cannot assign to 'x': it was declared
 * immutable`, and the file that asserts them is
 * `tests/safety/cases/strict_immutability.vel`; its passing twin declares the same
 * binding `mut`.
 */
class VelaImmutableAssignmentInspection : VelaInspectionBase(
    VelaInspectionRules.IMMUTABLE_ASSIGNMENT,
    "Assignment to an immutable binding",
    "<html>Vela's <code>x: T = e</code> declares an <b>immutable</b> binding: the compiler " +
        "refuses <code>x = e2</code> after it with " +
        "<code>safety error: cannot assign to 'x': it was declared immutable</code>. " +
        "The declaration that takes the <code>mut</code> keyword is the repair, and Vela has " +
        "no way to make an existing binding mutable other than to write it at its " +
        "declaration.<br><br>Not reported for parameters (a <code>mut</code> scalar parameter " +
        "is a copy) and not for arrays (<code>mut</code> does not repair a rebind).</html>",
)

/**
 * `"a" + "b"`.
 *
 * `vela: type error: string concatenation is not implemented in Vela 0.1`, from
 * `tests/safety/cases/strict_no_string_concatenation.vel`; the passing twin is the
 * same program with the named builtin, `concat("a", "b")`.
 */
class VelaStringConcatenationInspection : VelaInspectionBase(
    VelaInspectionRules.STRING_CONCATENATION,
    "'+' on strings",
    "<html>Vela 0.1 has no string concatenation operator: <code>\"a\" + \"b\"</code> is " +
        "refused with <code>type error: string concatenation is not implemented in Vela 0.1</code>. " +
        "The language joins strings with the named builtin <code>concat(a, b)</code>, which is " +
        "what this fix writes.</html>",
)

/**
 * `1 + 2.5`.
 *
 * `vela: type error: operator '+' mixes int and float`,
 * `tests/safety/cases/strict_no_implicit_conversion.vel`; the passing twins are the
 * two `strict_to_*_explicit.vel` programs, which write the conversion.
 */
class VelaIntFloatMixingInspection : VelaInspectionBase(
    VelaInspectionRules.INT_FLOAT_MIXING,
    "int and float mixed in one operation",
    "<html>Vela has no implicit numeric conversion: mixing an <code>int</code> and a " +
        "<code>float</code> in one operator is refused (<code>operator '+' mixes int and " +
        "float</code>). The fix converts the <b>int</b> operand with <code>to_float(...)</code>, " +
        "which is the conversion the language's own passing tests write; converting the float " +
        "side instead would change the program's meaning, so it is not offered.</html>",
)
