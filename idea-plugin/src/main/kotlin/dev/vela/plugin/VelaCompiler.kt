package dev.vela.plugin

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.file.Paths

/**
 * Where the compiler is, and what it said.
 *
 * The plugin owns no language knowledge, so the only configuration it has is the
 * path to the thing that does: `selfhost/build/vm.exe`, the Vela compiler built
 * from `selfhost/vm.c`.  Resolution order, most specific first: this setting,
 * `$VELA_VM`, the project's own directory *or any directory above it*,
 * `selfhost/build/vm[.exe]`, then `vm`/`vela` on `PATH`.
 *
 * The walk upwards matters more than it looks.  The obvious version of this —
 * `<project>/selfhost/build/vm.exe` — finds nothing as soon as somebody opens
 * `vela/tests/` or `vela/idea-plugin/` as the project, which is a normal thing to
 * do and was exactly what happened the first time this plugin was installed: the
 * compiler was never found, so the plugin had nothing to say and said nothing.
 * Looking upwards means any directory inside a Vela checkout works.
 */
@Service(Service.Level.APP)
@State(name = "VelaSettings", storages = [Storage("vela.xml")])
class VelaSettings : PersistentStateComponent<VelaSettings.State> {
    data class State(var compilerPath: String = "")

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    var compilerPath: String
        get() = state.compilerPath
        set(value) {
            state.compilerPath = value
        }

    companion object {
        fun getInstance(): VelaSettings =
            ApplicationManager.getApplication().getService(VelaSettings::class.java)
    }
}

/** One line of `vela: <kind>: <message>` with the location that follows it. */
data class VelaProblem(val line: Int, val kind: String, val message: String) {
    val severity: String get() = kind
}

object VelaCompiler {

    /** The names the compiler is installed under, in the order to prefer them. */
    private val NAMES = listOf("selfhost/build/vm.exe", "selfhost/build/vm")

    fun locate(project: Project?): String? {
        val configured = VelaSettings.getInstance().compilerPath
        if (configured.isNotBlank() && File(configured).isFile) return configured
        System.getenv("VELA_VM")?.let { if (File(it).isFile) return it }
        var dir = project?.basePath?.let { File(it) }
        var hops = 0
        while (dir != null && hops < 6) {
            for (name in NAMES) {
                val f = File(dir, name)
                if (f.isFile) return f.absolutePath
            }
            dir = dir.parentFile
            hops++
        }
        for (name in listOf("vm.exe", "vm", "vela.exe", "vela")) {
            val path = System.getenv("PATH")?.split(File.pathSeparator)
                ?.map { File(it, name) }?.firstOrNull { it.isFile }
            if (path != null) return path.absolutePath
        }
        return null
    }

    /**
     * The compiler's own runtime directory: the one holding `vela_runtime.h`.
     *
     * `vm.exe build <file>` defaults the include directory to the *relative*
     * path `runtime`, so the driver only finds the header when the working
     * directory happens to be the Vela root — measured, not assumed:
     * `vm.exe build <abs path>` from `vela/tests` prints
     * `fatal error C1083: cannot open include file: 'vela_runtime.h'` and exits 2,
     * while the same command from the repository root exits 0.  An editor has no
     * reason to be in the root, so the directory is derived from where the
     * compiler itself lives (`selfhost/build/vm.exe` -> `<root>/runtime`) and
     * passed as the driver's fourth argument, which an older `vm.exe` also
     * honours.  The callers that still run through `velaRoot` need it for the
     * working directory; this one is what makes the build independent of it.
     */
    fun runtimeDir(compiler: String): File? {
        var dir = File(compiler).absoluteFile.parentFile
        var hops = 0
        while (dir != null && hops < 4) {
            val candidate = File(dir, "runtime")
            if (File(candidate, "vela_runtime.h").isFile) return candidate
            dir = dir.parentFile
            hops++
        }
        return null
    }

    /** The Vela checkout the compiler was found in: the directory holding
     *  `runtime/`, and the only working directory `vm.exe build` is sane in. */
    fun velaRoot(compiler: String): File? = runtimeDir(compiler)?.parentFile

    /** `vm.exe build file.vel` or `check`, catching whatever it says. */
    fun run(compiler: String, mode: String, file: File): Pair<Int, String> {
        val cmd = GeneralCommandLine(compiler, mode, file.absolutePath)
            .withWorkDirectory(file.parentFile)
        val out = CapturingProcessHandler(cmd).runProcess(60_000)
        return out.exitCode to (out.stderr + out.stdout)
    }

    /**
     * The compiler writes `vela: safety error: <message>` and then the location
     * on the next line — `  at <path>:<line>` from the checker and resolver,
     * `  at line <N>` from the lexer and parser.  Those two lines are the whole
     * of the format, and this is the whole of the reader: no pattern matching on
     * English, no second parser to drift out of date.  A bare `vela: panic: ...`
     * line is the driver reporting that the refusal already happened, so it is
     * not a diagnostic of its own.
     */
    fun parse(text: String): List<VelaProblem> {
        val problems = mutableListOf<VelaProblem>()
        val lines = text.lines()
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trimEnd()
            if (!line.startsWith("vela: ")) continue
            val head = line.removePrefix("vela: ")
            val split = head.indexOf(": ")
            if (split <= 0) continue
            val kind = head.substring(0, split)
            if (kind == "panic") continue
            val message = head.substring(split + 2)
            val at = lines.getOrNull(i + 1)?.trim() ?: ""
            problems.add(VelaProblem(location(at, lines, i), kind, message))
        }
        return problems
    }

    private fun location(at: String, lines: List<String>, index: Int): Int {
        if (at.startsWith("at line ")) {
            return at.removePrefix("at line ").trim().toIntOrNull() ?: 1
        }
        if (at.startsWith("at ")) {
            val colon = at.lastIndexOf(':')
            if (colon > 0) {
                return at.substring(colon + 1).trim().toIntOrNull() ?: 1
            }
        }
        return 1
    }

    /** `a/b/hello.vel` -> `a/b/hello.exe` (or without the suffix elsewhere).
     *
     *  The same rule the compiler's own driver uses (`strip_vel` in
     *  `selfhost/parts/vm_main.vel`): strip the source suffix, add `.exe` on
     *  Windows.  Both suffixes count, or the plugin would look for an executable
     *  beside a `.vel` file under a name the compiler never wrote.
     */
    fun executableFor(file: File): File {
        val stem = velaStem(file.absolutePath)
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) {
            File("$stem.exe")
        } else {
            File(stem)
        }
        return exe
    }

    fun relativeName(file: VirtualFile): String =
        Paths.get(file.path).fileName.toString()
}
