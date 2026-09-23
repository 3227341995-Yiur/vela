package dev.vela.plugin

import com.intellij.psi.tree.IElementType

/**
 * One `IElementType` per syntax node kind.
 *
 * The platform identifies a PSI node by its element type, so a real tree needs
 * a real type for every node in it.  The names are the compiler's
 * (`VelaDefElement`, `VelaAugAssignElement`, ...) for the same reason the kinds
 * are: when the PSI tree and `vm.exe parse` disagree, the person reading the
 * diff should be reading one vocabulary, not two.
 *
 * The mapping is [of], and it is total by construction -- the `when` has no
 * `else` branch, so adding a kind to `VelaNodeKind` without giving it a type is
 * a compile error rather than a null at run time.  That matters more than it
 * looks: a null element type would make the platform build a tree with a hole
 * in it, and the hole would show up as "the structure view is empty for this
 * file" rather than as a crash.
 */
internal object VelaNodeTypes {

    @JvmField val MODULE: IElementType = IElementType("VELA_MODULE", VelaLanguage)
    @JvmField val MODULE_BLOCK: IElementType = IElementType("VELA_MODULE_BLOCK", VelaLanguage)
    @JvmField val BLOCK: IElementType = IElementType("VELA_BLOCK", VelaLanguage)
    @JvmField val UNDECLARED_BLOCK: IElementType = IElementType("VELA_UNDECLARED_BLOCK", VelaLanguage)
    @JvmField val DEF: IElementType = IElementType("VELA_DEF", VelaLanguage)
    @JvmField val PARAM: IElementType = IElementType("VELA_PARAM", VelaLanguage)
    @JvmField val STRUCT: IElementType = IElementType("VELA_STRUCT", VelaLanguage)
    @JvmField val FIELD: IElementType = IElementType("VELA_FIELD", VelaLanguage)
    @JvmField val DECL: IElementType = IElementType("VELA_DECL", VelaLanguage)
    @JvmField val ASSIGN: IElementType = IElementType("VELA_ASSIGN", VelaLanguage)
    @JvmField val AUGASSIGN: IElementType = IElementType("VELA_AUGASSIGN", VelaLanguage)
    @JvmField val EXPR: IElementType = IElementType("VELA_EXPR", VelaLanguage)
    @JvmField val RETURN: IElementType = IElementType("VELA_RETURN", VelaLanguage)
    @JvmField val IF: IElementType = IElementType("VELA_IF", VelaLanguage)
    @JvmField val ELSE: IElementType = IElementType("VELA_ELSE", VelaLanguage)
    @JvmField val WHILE: IElementType = IElementType("VELA_WHILE", VelaLanguage)
    @JvmField val FOR: IElementType = IElementType("VELA_FOR", VelaLanguage)
    @JvmField val BREAK: IElementType = IElementType("VELA_BREAK", VelaLanguage)
    @JvmField val CONTINUE: IElementType = IElementType("VELA_CONTINUE", VelaLanguage)
    @JvmField val PASS: IElementType = IElementType("VELA_PASS", VelaLanguage)
    @JvmField val INT: IElementType = IElementType("VELA_INT", VelaLanguage)
    @JvmField val FLOAT: IElementType = IElementType("VELA_FLOAT", VelaLanguage)
    @JvmField val STR: IElementType = IElementType("VELA_STR", VelaLanguage)
    @JvmField val BOOL: IElementType = IElementType("VELA_BOOL", VelaLanguage)
    @JvmField val NONE: IElementType = IElementType("VELA_NONE", VelaLanguage)
    @JvmField val NAME: IElementType = IElementType("VELA_NAME", VelaLanguage)
    @JvmField val CALL: IElementType = IElementType("VELA_CALL", VelaLanguage)
    @JvmField val BINOP: IElementType = IElementType("VELA_BINOP", VelaLanguage)
    @JvmField val UNARY: IElementType = IElementType("VELA_UNARY", VelaLanguage)
    @JvmField val BOOLOP: IElementType = IElementType("VELA_BOOLOP", VelaLanguage)
    @JvmField val CMP: IElementType = IElementType("VELA_CMP", VelaLanguage)
    @JvmField val INDEX: IElementType = IElementType("VELA_INDEX", VelaLanguage)
    @JvmField val ATTR: IElementType = IElementType("VELA_ATTR", VelaLanguage)
    @JvmField val LIST: IElementType = IElementType("VELA_LIST", VelaLanguage)
    @JvmField val SLICE: IElementType = IElementType("VELA_SLICE", VelaLanguage)
    @JvmField val ERROR: IElementType = IElementType("VELA_ERROR", VelaLanguage)
    // SPEC.md §13: an enum declaration, its variants, a `match` statement, the subject
    // it matches on, one arm, and one name a pattern binds.
    @JvmField val ENUM: IElementType = IElementType("VELA_ENUM", VelaLanguage)
    @JvmField val VARIANT: IElementType = IElementType("VELA_VARIANT", VelaLanguage)
    @JvmField val MATCH: IElementType = IElementType("VELA_MATCH", VelaLanguage)
    @JvmField val SUBJECT: IElementType = IElementType("VELA_SUBJECT", VelaLanguage)
    @JvmField val ARM: IElementType = IElementType("VELA_ARM", VelaLanguage)
    @JvmField val BINDING: IElementType = IElementType("VELA_BINDING", VelaLanguage)

    /** The element type for a node kind.  Total: no `else` on purpose. */
    fun of(kind: VelaNodeKind): IElementType = when (kind) {
        VelaNodeKind.MODULE -> MODULE
        VelaNodeKind.MODULE_BLOCK -> MODULE_BLOCK
        VelaNodeKind.BLOCK -> BLOCK
        VelaNodeKind.UNDECLARED_BLOCK -> UNDECLARED_BLOCK
        VelaNodeKind.DEF -> DEF
        VelaNodeKind.PARAM -> PARAM
        VelaNodeKind.STRUCT -> STRUCT
        VelaNodeKind.FIELD -> FIELD
        VelaNodeKind.DECL -> DECL
        VelaNodeKind.ASSIGN -> ASSIGN
        VelaNodeKind.AUGASSIGN -> AUGASSIGN
        VelaNodeKind.EXPR -> EXPR
        VelaNodeKind.RETURN -> RETURN
        VelaNodeKind.IF -> IF
        VelaNodeKind.ELSE -> ELSE
        VelaNodeKind.WHILE -> WHILE
        VelaNodeKind.FOR -> FOR
        VelaNodeKind.BREAK -> BREAK
        VelaNodeKind.CONTINUE -> CONTINUE
        VelaNodeKind.PASS -> PASS
        VelaNodeKind.INT -> INT
        VelaNodeKind.FLOAT -> FLOAT
        VelaNodeKind.STR -> STR
        VelaNodeKind.BOOL -> BOOL
        VelaNodeKind.NONE -> NONE
        VelaNodeKind.NAME -> NAME
        VelaNodeKind.CALL -> CALL
        VelaNodeKind.BINOP -> BINOP
        VelaNodeKind.UNARY -> UNARY
        VelaNodeKind.BOOLOP -> BOOLOP
        VelaNodeKind.CMP -> CMP
        VelaNodeKind.INDEX -> INDEX
        VelaNodeKind.ATTR -> ATTR
        VelaNodeKind.LIST -> LIST
        VelaNodeKind.SLICE -> SLICE
        VelaNodeKind.ERROR -> ERROR
        VelaNodeKind.ENUM -> ENUM
        VelaNodeKind.VARIANT -> VARIANT
        VelaNodeKind.MATCH -> MATCH
        VelaNodeKind.SUBJECT -> SUBJECT
        VelaNodeKind.ARM -> ARM
        VelaNodeKind.BINDING -> BINDING
    }

    /**
     * Every node type, for the checks that need to enumerate them.
     *
     * The order is `VelaNodeKind`'s, and the list is built from [of], so it
     * cannot list a type the parser never makes or miss one it does.
     */
    @JvmField val ALL: List<IElementType> = VelaNodeKind.values().map { of(it) }
}
