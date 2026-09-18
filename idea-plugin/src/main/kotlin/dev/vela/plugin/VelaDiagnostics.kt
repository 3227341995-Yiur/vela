package dev.vela.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The compiler's verdict on a *buffer*, not on a file.
 *
 * This is the one place the plugin asks `vm.exe check` a question, and it exists
 * because asking it the obvious way was wrong: the annotator used to hand the
 * compiler the path on disk, so while you were typing it checked the *last saved*
 * version.  Every diagnostic therefore lagged one save behind the editor, which
 * for a language whose whole claim is "the compiler decides what a program means,
 * and the editor asks it" is the wrong way round — the editor was showing what
 * the compiler thought of a file that no longer existed.
 *
 * So the text is written to a temporary file and that is what the compiler reads.
 * The compiler reports locations by *line*, and `VelaCompiler.parse` keeps the
 * line and drops the path, so the temporary path never reaches the user: line 12
 * of the buffer is line 12 of the copy, and the squiggle lands where it should.
 *
 * Two callers share this: the annotator (squiggles while typing) and anything
 * else that wants the same answer.  One cache, keyed by compiler and content, so
 * a keystroke that does not change the text cannot start a process, and two
 * features asking about the same buffer run the compiler once.
 */
object VelaDiagnostics {

    private data class Key(val compiler: String, val hash: Long, val length: Int)

    private val cache = ConcurrentHashMap<Key, List<VelaProblem>>()

    /**
     * The compiler's problems for `text`, or null when no compiler can be found.
     * Null is distinct from an empty list on purpose: "there is nothing wrong"
     * and "nothing could be asked" are different facts, and the annotator draws
     * them differently.
     */
    fun check(project: Project?, file: VirtualFile?, text: CharSequence): List<VelaProblem>? {
        val compiler = VelaCompiler.locate(project) ?: return null
        if (file != null && !file.isInLocalFileSystem) return emptyList()
        return checkWithCompiler(compiler, text)
    }

    /**
     * Run a named compiler over this text.  Split out from [check] so it can be
     * exercised without an IDE — a project, a virtual file and a settings
     * component are all that [check] adds, and this is the part that runs a
     * process and reads its output.
     */
    fun checkWithCompiler(compiler: String, text: CharSequence): List<VelaProblem> {
        val key = Key(compiler, hashOf(text), text.length)
        cache[key]?.let { return it }
        val tmp = File.createTempFile("vela-check-", ".vel")
        try {
            tmp.writeText(text.toString())
            val (_, output) = VelaCompiler.run(compiler, "check", tmp)
            val problems = VelaCompiler.parse(output)
            if (cache.size > 16) cache.clear()
            cache[key] = problems
            return problems
        } finally {
            // Best effort: on Windows a file the compiler just read is not always
            // deletable on the first try, and a stray temp file is not worth an
            // exception in the middle of annotating.
            tmp.delete()
        }
    }

    private fun hashOf(text: CharSequence): Long {
        var h = 1125899906842597L
        for (i in 0 until text.length) h = 31 * h + text[i].code
        return h
    }
}
