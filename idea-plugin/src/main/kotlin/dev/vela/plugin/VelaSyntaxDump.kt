package dev.vela.plugin

/**
 * The compiler's own syntax-tree dump, produced from this parser's tree.
 *
 * This is the other half of the argument in `VelaSyntax.kt`.  Writing a parser
 * is not proof that it agrees with the compiler; printing its tree in the
 * compiler's canonical format and diffing the two over the whole corpus is.
 * `selfhost/parts/dump.vel` fixes the format -- the recorded digests in
 * `tests/golden/digests.txt` hold the compiler's own printer to it -- so this
 * file is a transcription of `d_node`, `d_body`, `d_chain`, `op_name`, `d_type`
 * and `d_str` from that file, not a format of its own.
 *
 * A difference in any of those four functions would show up as a diff on some
 * file in the corpus, so the transcription has to be literal:
 *
 *  * `module` prints, then a `block` one level deeper, then the statements one
 *    level deeper again -- which is why the module node in this parser holds a
 *    block whose children are the statements.
 *  * A `def` prints its parameters as `param` lines one level deeper (they are
 *    *not* separate d_node calls at the same depth), then its body.
 *  * A struct prints its fields first and its methods second, each in source
 *    order, so a struct whose methods precede a field still prints the field
 *    first.
 *  * `if` prints the condition, the then-block, and -- only when an else part
 *    exists -- an `else` line one level deeper with the else's contents one
 *    level deeper than that.
 *  * An operator is printed through `op_name`, so an operator code the dump has
 *    no name for prints `op?<code>` here instead of silently printing some
 *    other operator, which is what the compiler's own fall-through does.
 */
object VelaSyntaxDump {

    /** The whole tree, in the compiler's format, one node per line. */
    fun dump(tree: VelaSyntaxTree): String {
        val sb = StringBuilder(4096)
        dumpNode(sb, tree, tree.root, 0)
        // Every node already ended its own line, so there is nothing to add: the
        // compiler's printer does the same and its output ends with exactly one
        // newline after the last node.  (Getting this wrong costs one blank line
        // at the end of every file, which is exactly the kind of difference this
        // harness exists to catch.)
        return sb.toString()
    }

    /**
     * The token-only dump.
     *
     * The compiler's `class AstDiff` harness runs `vm.exe parse`, so this is
     * here for a second, cheaper comparison: it prints the shape of the tree
     * without the compiler at all, which is what lets the recovery tests assert
     * something about deliberately broken files, where the compiler's answer is
     * only "no".
     */
    fun dumpShape(tree: VelaSyntaxTree): String {
        val sb = StringBuilder(1024)
        shapeNode(sb, tree.root, 0)
        return sb.toString()
    }

    private fun shapeNode(sb: StringBuilder, n: VelaSyntaxNode, depth: Int) {
        for (i in 0 until depth) sb.append("  ")
        sb.append(n.kind.name.lowercase())
        sb.append(' ').append(n.startTok).append("..").append(n.endTok)
        sb.append('\n')
        for (c in n.children) shapeNode(sb, c, depth + 1)
    }

    // ------------------------------------------------------------------ nodes

    /**
     * The element type name a node kind becomes in the PSI tree.
     *
     * Exposed for `psi-tree-diff.ps1`, which compares the platform tree's element
     * types against the parser's node kinds: it has to ask *this* table what a kind
     * is called, rather than spell `VELA_` + the kind name itself, or a renamed
     * element type would make the comparison pass by accident.
     */
    fun elementTypeNameOf(kind: VelaNodeKind): String = VelaNodeTypes.of(kind).toString()

    /**
     * Every element type a syntax node can become.
     *
     * Also for `psi-tree-diff.ps1`, which has to tell an empty *node* from a leaf
     * token: an `extern c` declaration carries an empty `block` child on purpose
     * (the compiler's dump prints a `block` line for it, so the parser has to build
     * one), and that child is a zero-length element with no children of its own --
     * indistinguishable from a leaf unless the type is known to be a node type.
     */
    fun nodeElementTypeNames(): Set<String> = VelaNodeTypes.ALL.map { it.toString() }.toSet()

    private fun indent(sb: StringBuilder, depth: Int) {
        var i = 0
        while (i < depth) {
            sb.append("  ")
            i++
        }
    }

    private fun dumpBody(sb: StringBuilder, tree: VelaSyntaxTree, body: VelaSyntaxNode?, depth: Int) {
        indent(sb, depth)
        sb.append("block\n")
        if (body == null) return
        for (c in body.children) dumpNode(sb, tree, c, depth + 1)
    }

    private fun dumpChain(sb: StringBuilder, tree: VelaSyntaxTree, parent: VelaSyntaxNode, depth: Int) {
        for (c in parent.children) dumpNode(sb, tree, c, depth)
    }

    private fun dumpNode(sb: StringBuilder, tree: VelaSyntaxTree, n: VelaSyntaxNode, depth: Int) {
        when (n.kind) {
            VelaNodeKind.MODULE -> {
                indent(sb, depth)
                sb.append("module\n")
                // The compiler's module node holds a chain of statements and its
                // dumper prints "block" for it; here the block is a node.
                val body = n.children.firstOrNull()
                dumpBody(sb, tree, body, depth + 1)
            }

            VelaNodeKind.MODULE_BLOCK, VelaNodeKind.BLOCK -> {
                // A block reached as a statement position: only happens through
                // recovery, and the compiler's printer has no such case, so it
                // prints as a block would.
                dumpBody(sb, tree, n, depth)
            }

            VelaNodeKind.UNDECLARED_BLOCK -> {
                // A body that could not be opened.  Nothing was written, so
                // nothing is printed.
            }

            VelaNodeKind.ERROR -> {
                // A construct the compiler never builds, because it stops on the
                // first error instead.  Printing it would put a line into the
                // diff that the compiler cannot have; the harness counts these
                // separately and reports them as "this file is not valid Vela".
            }

            VelaNodeKind.DEF -> {
                indent(sb, depth)
                sb.append("def name=").append(n.name)
                sb.append(" pure=").append(flag(n.flags, 4))
                sb.append(" ret=").append(n.retType.ifEmpty { "-" })
                sb.append('\n')
                for (c in n.children) {
                    if (c.kind == VelaNodeKind.PARAM) {
                        indent(sb, depth + 1)
                        sb.append("param name=").append(c.name)
                        sb.append(" mut=").append(flag(c.flags, 1))
                        sb.append(" type=").append(c.typeText.ifEmpty { "-" })
                        sb.append('\n')
                    }
                }
                val body = n.children.firstOrNull {
                    it.kind == VelaNodeKind.BLOCK || it.kind == VelaNodeKind.UNDECLARED_BLOCK
                }
                dumpBody(sb, tree, body, depth + 1)
            }

            VelaNodeKind.PARAM -> {
                indent(sb, depth)
                sb.append("param name=").append(n.name)
                sb.append(" mut=").append(flag(n.flags, 1))
                sb.append(" type=").append(n.typeText.ifEmpty { "-" })
                sb.append('\n')
            }

            VelaNodeKind.STRUCT -> {
                indent(sb, depth)
                sb.append("struct name=").append(n.name).append('\n')
                // fields first, then methods -- what the compiler's dumper does,
                // and the order its AST stores them in
                for (c in n.children) {
                    if (velaIsFieldMember(c)) dumpField(sb, c, depth + 1)
                }
                for (c in n.children) {
                    if (c.kind == VelaNodeKind.DEF) dumpNode(sb, tree, c, depth + 1)
                }
            }

            VelaNodeKind.FIELD -> dumpField(sb, n, depth)

            /*
             * SPEC.md §13.  The six cases below are the compiler's `dump.vel` node
             * kinds 35 (enum), 36 (variant), 37 (match) and 38 (arm), plus the two
             * wrappers its printer writes for them: `subject` on a line of its own
             * before the expression, and one `binding name=` line per name a pattern
             * binds, before the arm's body.
             *
             * `fields=` and `binds=` are printed from the node's own children rather
             * than from a stored count, so a tree can never say "2 fields" over one
             * field line: the compiler stores a count and this counts, and the two
             * agree on every file `ast-diff.ps1` compares.
             */
            VelaNodeKind.ENUM -> {
                indent(sb, depth)
                sb.append("enum name=").append(n.name).append('\n')
                for (c in n.children) dumpNode(sb, tree, c, depth + 1)
            }

            VelaNodeKind.VARIANT -> {
                indent(sb, depth)
                sb.append("variant name=").append(n.name)
                sb.append(" fields=").append(n.children.size)
                sb.append('\n')
                for (c in n.children) dumpField(sb, c, depth + 1)
            }

            VelaNodeKind.MATCH -> {
                indent(sb, depth)
                sb.append("match\n")
                for (c in n.children) dumpNode(sb, tree, c, depth + 1)
            }

            VelaNodeKind.SUBJECT -> {
                indent(sb, depth)
                sb.append("subject\n")
                for (c in n.children) dumpNode(sb, tree, c, depth + 1)
            }

            VelaNodeKind.ARM -> {
                indent(sb, depth)
                sb.append("arm pattern=")
                // `else` is the arm that names no variant; the flag is the parser's
                // (the same bit the rest of the tree uses for a shape flag), and the
                // name is empty for it, so an `else` arm cannot be confused with a
                // variant whose name happens to be spelled the same way -- which the
                // language does not allow anyway, `else` being a keyword.
                sb.append(if (n.flags and 1 != 0) "else" else n.name)
                sb.append(" binds=").append(n.children.count { it.kind == VelaNodeKind.BINDING })
                sb.append('\n')
                for (c in n.children) {
                    if (c.kind == VelaNodeKind.BINDING) {
                        indent(sb, depth + 1)
                        sb.append("binding name=").append(c.name).append('\n')
                    }
                }
                val body = n.children.firstOrNull {
                    it.kind == VelaNodeKind.BLOCK || it.kind == VelaNodeKind.UNDECLARED_BLOCK
                }
                dumpBody(sb, tree, body, depth + 1)
            }

            VelaNodeKind.BINDING -> {
                indent(sb, depth)
                sb.append("binding name=").append(n.name).append('\n')
            }

            VelaNodeKind.DECL -> {
                indent(sb, depth)
                sb.append("decl name=").append(n.name)
                sb.append(" mut=").append(flag(n.flags, 1))
                sb.append(" type=").append(n.typeText.ifEmpty { "-" })
                sb.append('\n')
                val v = n.children.firstOrNull()
                if (v != null) dumpNode(sb, tree, v, depth + 1)
            }

            VelaNodeKind.ASSIGN -> {
                indent(sb, depth)
                sb.append("assign\n")
                for (c in n.children) dumpNode(sb, tree, c, depth + 1)
            }

            VelaNodeKind.AUGASSIGN -> {
                indent(sb, depth)
                sb.append("augassign op=").append(velaOpName(n.op)).append('\n')
                for (c in n.children) dumpNode(sb, tree, c, depth + 1)
            }

            VelaNodeKind.EXPR -> {
                indent(sb, depth)
                sb.append("expr\n")
                for (c in n.children) dumpNode(sb, tree, c, depth + 1)
            }

            VelaNodeKind.RETURN -> {
                indent(sb, depth)
                sb.append("return has_value=").append(if (n.children.isEmpty()) "0" else "1")
                sb.append('\n')
                for (c in n.children) dumpNode(sb, tree, c, depth + 1)
            }

            VelaNodeKind.IF -> {
                indent(sb, depth)
                sb.append("if\n")
                val cond = n.children.firstOrNull()
                if (cond != null) dumpNode(sb, tree, cond, depth + 1)
                var i = 1
                while (i < n.children.size) {
                    val c = n.children[i]
                    if (c.kind == VelaNodeKind.ELSE) {
                        indent(sb, depth + 1)
                        sb.append("else\n")
                        dumpElseBody(sb, tree, c, depth + 2)
                    } else {
                        dumpBody(sb, tree, c, depth + 1)
                    }
                    i++
                }
            }

            VelaNodeKind.ELSE -> {
                // Only reachable if an else node is handed to the node printer
                // directly, which the if case above avoids.
                indent(sb, depth)
                sb.append("else\n")
                dumpElseBody(sb, tree, n, depth + 1)
            }

            VelaNodeKind.WHILE -> {
                indent(sb, depth)
                sb.append("while\n")
                val cond = n.children.firstOrNull()
                if (cond != null) dumpNode(sb, tree, cond, depth + 1)
                var i = 1
                while (i < n.children.size) {
                    dumpBody(sb, tree, n.children[i], depth + 1)
                    i++
                }
            }

            VelaNodeKind.FOR -> {
                indent(sb, depth)
                sb.append("for var=").append(n.name)
                sb.append(" parallel=").append(flag(n.flags, 1))
                sb.append(" has_step=").append(if (n.op != 0) "1" else "0")
                sb.append('\n')
                // every child but the body is a range argument, in order
                for (c in n.children) {
                    if (c.kind == VelaNodeKind.BLOCK || c.kind == VelaNodeKind.UNDECLARED_BLOCK) continue
                    dumpNode(sb, tree, c, depth + 1)
                }
                val body = n.children.firstOrNull {
                    it.kind == VelaNodeKind.BLOCK || it.kind == VelaNodeKind.UNDECLARED_BLOCK
                }
                dumpBody(sb, tree, body, depth + 1)
            }

            VelaNodeKind.BREAK -> {
                indent(sb, depth)
                sb.append("break\n")
            }

            VelaNodeKind.CONTINUE -> {
                indent(sb, depth)
                sb.append("continue\n")
            }

            VelaNodeKind.PASS -> {
                indent(sb, depth)
                sb.append("pass\n")
            }

            VelaNodeKind.INT -> {
                indent(sb, depth)
                sb.append("int ").append(n.intValue).append('\n')
            }

            VelaNodeKind.FLOAT -> {
                indent(sb, depth)
                sb.append("float ")
                // printed as written: exact, and independent of how a C library
                // happens to format a double
                sb.append(n.floatText)
                sb.append('\n')
            }

            VelaNodeKind.STR -> {
                indent(sb, depth)
                sb.append("str \"").append(escapedLiteral(n)).append("\"\n")
            }

            VelaNodeKind.BOOL -> {
                indent(sb, depth)
                sb.append("bool ").append(n.intValue).append('\n')
            }

            VelaNodeKind.NONE -> {
                indent(sb, depth)
                sb.append("none\n")
            }

            VelaNodeKind.NAME -> {
                indent(sb, depth)
                sb.append("name ").append(n.name).append('\n')
            }

            VelaNodeKind.CALL -> {
                indent(sb, depth)
                sb.append("call\n")
                val callee = n.children.firstOrNull()
                if (callee != null) dumpNode(sb, tree, callee, depth + 1)
                var i = 1
                while (i < n.children.size) {
                    dumpNode(sb, tree, n.children[i], depth + 1)
                    i++
                }
            }

            VelaNodeKind.BINOP -> {
                indent(sb, depth)
                sb.append("binop op=").append(velaOpName(n.op)).append('\n')
                for (c in n.children) dumpNode(sb, tree, c, depth + 1)
            }

            VelaNodeKind.UNARY -> {
                indent(sb, depth)
                sb.append("unary op=").append(velaUnaryOpName(n.op)).append('\n')
                for (c in n.children) dumpNode(sb, tree, c, depth + 1)
            }

            VelaNodeKind.BOOLOP -> {
                indent(sb, depth)
                sb.append("boolop op=").append(if (n.op == VelaKw.AND) "and" else "or").append('\n')
                for (c in n.children) dumpNode(sb, tree, c, depth + 1)
            }

            VelaNodeKind.CMP -> {
                indent(sb, depth)
                sb.append("cmp op=").append(velaOpName(n.op)).append('\n')
                for (c in n.children) dumpNode(sb, tree, c, depth + 1)
            }

            VelaNodeKind.INDEX -> {
                indent(sb, depth)
                sb.append("index\n")
                for (c in n.children) dumpNode(sb, tree, c, depth + 1)
            }

            VelaNodeKind.ATTR -> {
                indent(sb, depth)
                sb.append("attr name=").append(n.name).append('\n')
                for (c in n.children) dumpNode(sb, tree, c, depth + 1)
            }

            VelaNodeKind.LIST -> {
                indent(sb, depth)
                sb.append("list\n")
                for (c in n.children) dumpNode(sb, tree, c, depth + 1)
            }

            VelaNodeKind.SLICE -> {
                indent(sb, depth)
                sb.append("slice has_lower=")
                sb.append(if (n.children.size > 1) "1" else "0")
                sb.append(" has_upper=")
                sb.append(if (n.children.size > 2) "1" else "0")
                sb.append('\n')
                for (c in n.children) dumpNode(sb, tree, c, depth + 1)
            }
        }
    }

    /**
     * The contents of an `else`, printed as the compiler's dumper prints them.
     *
     * Three shapes, and the compiler answers all three the same way -- with the
     * `if` printed directly, one level deeper than the `else` line:
     *
     *  * `else if c { ... }`  -- the else's body *is* an if node;
     *  * `else { if c { ... } }` -- a block whose only statement is an if;
     *  * anything else -- a block, printed as `block` with its statements.
     *
     * The second case is not obvious and is not this parser's invention: the
     * compiler's dumper asks whether the else's first node is an if node, and a
     * one-statement block holding an if reaches it as that block, yet
     * `vm.exe parse` prints the if.  Rather than guess why, the behaviour was
     * measured (`tests/build/bom_first_byte.vel` apart, `selfhost/parts/eval.vel`
     * is a 2877-line file that contains `} else { if ... }` and is diffed line
     * for line), and what is reproduced here is what was measured.
     */
    private fun dumpElseBody(sb: StringBuilder, tree: VelaSyntaxTree, elseNode: VelaSyntaxNode, depth: Int) {
        val first = elseNode.children.firstOrNull() ?: return
        if (first.kind == VelaNodeKind.IF) {
            dumpNode(sb, tree, first, depth)
            return
        }
        if (isSingleIfBlock(first)) {
            dumpNode(sb, tree, first.children[0], depth)
            return
        }
        dumpBody(sb, tree, first, depth)
    }

    /**
     * A body that holds exactly one statement, and that statement is an `if`.
     *
     * An undeclared block (one whose `{` was missing, so recovery never opened
     * it) does not count: its child was not written between braces and must not
     * be hoisted out of it.
     */
    private fun isSingleIfBlock(n: VelaSyntaxNode): Boolean =
        n.kind == VelaNodeKind.BLOCK && n.children.size == 1 &&
            n.children[0].kind == VelaNodeKind.IF

    private fun dumpField(sb: StringBuilder, n: VelaSyntaxNode, depth: Int) {
        indent(sb, depth)
        sb.append("field name=").append(n.name)
        sb.append(" type=").append(n.typeText.ifEmpty { "-" })
        sb.append('\n')
    }

    private fun flag(flags: Int, bit: Int): String = if (flags and bit != 0) "1" else "0"

    /**
     * A string literal as the compiler's `d_str` prints it: the bytes it
     * denotes, escaped with `\"`, `\\`, `\n`, `\t`, `\r`, and `\xNN` for
     * anything outside printable ASCII -- so a diff can never hide inside a
     * literal, and a byte that is not something the printer has a name for is
     * still compared exactly.
     */
    private fun escapedLiteral(n: VelaSyntaxNode): String {
        val bytes = n.bytes ?: ByteArray(0)
        val sb = StringBuilder(bytes.size + 8)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            when (c) {
                34 -> sb.append("\\\"")
                92 -> sb.append("\\\\")
                10 -> sb.append("\\n")
                9 -> sb.append("\\t")
                13 -> sb.append("\\r")
                else -> {
                    if (c in 32..126) {
                        sb.append(c.toChar())
                    } else {
                        sb.append("\\x")
                        sb.append("0123456789abcdef"[c ushr 4])
                        sb.append("0123456789abcdef"[c and 15])
                    }
                }
            }
        }
        return sb.toString()
    }
}

/**
 * String literals, byte for byte.
 *
 * `vela_unescape` (runtime/vela_runtime.h) walks the *bytes* of a literal and
 * writes the bytes it denotes, and the dump escapes those bytes.  So a literal
 * is bytes here too: the source is read as ISO-8859-1 (which maps a byte to the
 * character with the same value, and back again), and nothing is ever decoded
 * as UTF-8 -- a `"\u00e9"` must print as the three bytes `\xc3\xa9`, not as one
 * character.
 */
internal object VelaStrings {

    /**
     * The bytes a literal denotes.
     *
     * With no escape in it, the literal's bytes are its characters' bytes.
     * With escapes, they are `vela_unescape`'s output: `\n \t \r \0 \a \b \f \v
     * \\ \" \'` are themselves, `\xHH` and `\uHHHH` are hex, and any other
     * escaped character stands for itself (so `\q` is `q`).
     */
    fun literalBytes(text: String, hasEscapes: Boolean): ByteArray {
        val chars = text.toCharArray()
        if (!hasEscapes) {
            val out = ByteArray(chars.size)
            for (i in chars.indices) out[i] = (chars[i].code and 0xFF).toByte()
            return out
        }
        val out = ArrayList<Int>(chars.size)
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            if (c != '\\' || i + 1 >= chars.size) {
                out.add(c.code and 0xFF)
                i++
                continue
            }
            i++
            val e = chars[i]
            i++
            when (e) {
                'n' -> out.add(10)
                't' -> out.add(9)
                'r' -> out.add(13)
                '0' -> out.add(0)
                'a' -> out.add(7)
                'b' -> out.add(8)
                'f' -> out.add(12)
                'v' -> out.add(11)
                '\\' -> out.add(92)
                '"' -> out.add(34)
                '\'' -> out.add(39)
                'x' -> {
                    var v = 0
                    var k = 0
                    while (k < 2 && i < chars.size && hexVal(chars[i]) >= 0) {
                        v = v * 16 + hexVal(chars[i])
                        i++
                        k++
                    }
                    out.add(v)
                }
                'u' -> {
                    var v = 0
                    var k = 0
                    while (k < 4 && i < chars.size && hexVal(chars[i]) >= 0) {
                        v = v * 16 + hexVal(chars[i])
                        i++
                        k++
                    }
                    // the runtime writes \u as UTF-8, one to three bytes
                    if (v < 0x80) {
                        out.add(v)
                    } else if (v < 0x800) {
                        out.add(0xC0 or (v shr 6))
                        out.add(0x80 or (v and 0x3F))
                    } else {
                        out.add(0xE0 or (v shr 12))
                        out.add(0x80 or ((v shr 6) and 0x3F))
                        out.add(0x80 or (v and 0x3F))
                    }
                }
                else -> out.add(e.code and 0xFF)
            }
        }
        val arr = ByteArray(out.size)
        for (j in out.indices) arr[j] = out[j].toByte()
        return arr
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
