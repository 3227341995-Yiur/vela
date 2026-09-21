package dev.vela.plugin

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RunConfigurationWithSuppressedDefaultDebugAction
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.execution.ParametersListUtil
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import java.io.OutputStream
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * The `Vela` run configuration: `vm.exe run <file>`, interpreted, into one
 * console.
 *
 * The plugin still owns no language knowledge.  A run configuration is a *saved
 * command line* — a file, a compiler and some arguments — and every question
 * about whether the program is legal is still answered by the compiler, exactly
 * as it is for the actions in `VelaActions.kt`.  What a configuration adds over
 * the actions is that those three facts can be named, stored and re-run from the
 * Run toolwindow like any other target in the IDE.
 *
 * Run *interprets*; it does not build.  That is a deliberate split, not an
 * unfinished path: interpreting needs no C compiler, writes nothing next to the
 * user's source, and starts showing output immediately, which is what a Run
 * button should promise.  Compiling is `Vela.BuildAndRun` in `VelaActions.kt`,
 * and the two are not duplicates of each other — one is "show me what this
 * program does", the other is "produce the binary", and each is a worse answer
 * to the other's question.  See `VelaRunConfiguration.getState` for the price
 * being paid: the interpreter is much slower than the compiled binary.
 *
 * Everything below lives in this one file because it is one feature: the type,
 * the factory, the serialized options, the configuration, its settings editor,
 * and the process the editor's Run button ends up driving.  Six files would be
 * six files to keep in step and nothing else.
 */

/** The class `plugin.xml` registers as `<configurationType>`. */
class VelaRunConfigurationType : ConfigurationTypeBase(
    // The id, not the display name, is what serialized settings and the Run
    // dialog key on, so it is deliberately not "Vela": an id equal to the
    // display name is the collision-prone choice, and this plugin shares one
    // global namespace with every other plugin.  "Vela" is the display name
    // below — that is the string the user reads.
    "VelaRunConfiguration",
    "Vela",
    "Build a Vela file with vm.exe, then run the executable it writes",
    // Resolved through the platform's icon cache, so the same call the file
    // type makes is enough and there is no second icon to keep in step.
    IconLoader.getIcon("/icons/vela.svg", VelaRunConfigurationType::class.java),
) {
    /**
     * Exactly one factory, so the Run/Debug dialog shows a single "Vela" item
     * and never asks the user which of two identical factories they meant.
     */
    init {
        addFactory(VelaRunConfigurationFactory(this))
    }
}

/**
 * Creates configurations.  `getOptionsClass` is the interesting one: it names
 * the class the platform instantiates and serializes, and `RunConfigurationBase`
 * then hands that instance to `loadState`, so nothing in this file parses or
 * writes XML.
 */
class VelaRunConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {

    override fun getId(): String = "VelaRunConfigurationFactory"

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        VelaRunConfiguration(project, this, "Vela")

    /**
     * A Vela target is applicable to every project.  A missing compiler is
     * `checkConfiguration`'s answer to give, not this one — saying "not
     * applicable" here would remove the type from the dialog entirely and leave
     * the user no field in which to state the compiler path.
     */
    override fun isApplicable(project: Project): Boolean = true

    override fun getOptionsClass(): Class<out RunConfigurationOptions> =
        VelaRunConfigurationOptions::class.java
}

/**
 * What a Vela configuration remembers, as three strings.
 *
 * `BaseState.string()` is the platform's own way of saying "one persisted
 * `String?`": the delegate registers itself with the state object, so the field
 * name in the XML, the default value and the modification count all come from
 * the platform rather than from a second, hand-written reader.
 *
 * The file is not constrained to `.vel`/`.vela` here.  A run configuration
 * names a file the compiler will be asked to build, and whether that file is a
 * legal Vela program is the compiler's verdict, not this class's.
 */
class VelaRunConfigurationOptions : RunConfigurationOptions() {
    var filePath: String? by string()
    var compilerPath: String? by string()
    var programArguments: String? by string()
}

/**
 * The saved command line.
 *
 * It keeps no state of its own: everything lives in the options object, so the
 * platform's own copying, cloning and serialization keep working.  What it does
 * add is the one piece of behaviour a variable-free configuration needs — a
 * brand-new configuration starts out pointing at the file the user was looking
 * at, so `Run` works before anything has been filled in by hand.
 *
 * `RunConfigurationWithSuppressedDefaultDebugAction` is a marker interface, and
 * it is the honest way to say "Run only" rather than a hand-rolled guard.
 *
 * Vela ships no debugger: there is no compiled-in support for breakpoints,
 * stepping or inspecting a frame, and this plugin has nothing that could speak
 * to such a thing.  The only thing this configuration knows how to do is ask the
 * compiler to build and then start the executable, with nothing in front of it.
 * So a Debug that appeared to work would not be a marginally worse debugger — it
 * would be a run with a debugger-shaped label on it, and the user would only
 * find that out at the point where they needed a breakpoint to stop.  Refusing
 * up front is the same fact, told at a time when it is still cheap to act on.
 *
 * Without the marker the platform's catch-all `DapProgramRunner` would happily
 * accept the Debug executor — it is written to accept any profile that does
 * *not* say this — and would then have nothing to attach.  With it,
 * `DapProgramRunner.canRun` refuses, no other runner claims a Vela profile, and
 * Debug is simply not offered.  Verified against the installed
 * `intellij.platform.dap.jar`: `DapProgramRunner.canRun` tests exactly this
 * interface (and `RunConfigurationWithSuppressedDefaultRunAction`, for the
 * mirror-image case).
 */
class VelaRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : com.intellij.execution.configurations.RunConfigurationBase<VelaRunConfigurationOptions>(
    project,
    factory,
    name,
),
    RunConfigurationWithSuppressedDefaultDebugAction {

    private val options: VelaRunConfigurationOptions
        get() = getOptions() as VelaRunConfigurationOptions

    fun getFilePath(): String? = options.filePath

    fun setFilePath(path: String?) {
        options.filePath = path
    }

    fun getCompilerPath(): String? = options.compilerPath

    fun setCompilerPath(path: String?) {
        options.compilerPath = path
    }

    fun getProgramArguments(): String? = options.programArguments

    fun setProgramArguments(arguments: String?) {
        options.programArguments = arguments
    }

    /**
     * This runs when the Run/Debug dialog adds a configuration, which is the
     * one moment the editor's selection is unambiguously "the thing they meant".
     * A template created at startup has no such thing, and a configuration the
     * user typed a path into keeps what they typed.
     */
    override fun onNewConfigurationCreated() {
        super.onNewConfigurationCreated()
        if (!options.filePath.isNullOrBlank()) return
        val selected = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            ?: return
        if (!isVelaFileName(selected.name)) return
        options.filePath = FileUtil.toSystemDependentName(selected.path)
    }

    /**
     * A file there is nothing to interpret.  That is an error, and this is the
     * right place for it: the dialog refuses to start, and the message is in
     * front of the user next to the field they need to fix.
     *
     * A missing *compiler* is deliberately not checked here.  It is reported in
     * the console by `VelaRunState`, and reporting it in both places would let
     * "before launch" refuse the run with a modal error while the console — the
     * one place the user is actually looking — stays empty.  One fact, one
     * report, and the console is where a run that did not happen has to explain
     * itself.
     */
    override fun checkConfiguration() {
        val raw = options.filePath?.trim().orEmpty()
        if (raw.isEmpty()) {
            throw RuntimeConfigurationError(
                "No Vela file to run. Choose one with the browse button, or open " +
                    "a .vel file and create the configuration again.",
            )
        }
        val file = File(FileUtil.toSystemDependentName(raw))
        if (!file.isFile) {
            throw RuntimeConfigurationError("Vela file not found: ${file.absolutePath}")
        }
    }

    override fun getConfigurationEditor(): SettingsEditor<VelaRunConfiguration> =
        VelaRunConfigurationEditor(project)

    /**
     * `vm.exe run <file>`: the interpreter, on the source file, directly.
     *
     * `run` and not `build`.  A Run button that compiles first is the wrong shape
     * for the button and was the direct cause of the failure this configuration
     * was reported for: it needs a C compiler and MSVC's environment, it writes a
     * `.c`, a `.obj` and an `.exe` next to the user's source, and it inherits a
     * whole class of include-path problems — the shipped `vm.exe` resolves its
     * runtime include directory to the *relative* string `runtime`, which fails
     * with `fatal error C1083: cannot open include file: 'vela_runtime.h'` unless
     * the process happens to be standing in the Vela root.  The user's IDE project
     * was `vela/tests`, so every run they attempted took that path.  Interpreting
     * has none of it: one child, no artifact, nothing to find.
     *
     * The cost is honest and worth stating: what the console shows is the
     * *interpreter's* output, and the interpreter is much slower than a compiled
     * binary — the corpus's `run` cases measure both, and the README puts the
     * interpreter at roughly 76x a compiled binary on a loop MSVC cannot
     * vectorise.  That is the right default for Run (it always works, it starts
     * at once, and it writes nothing); a compiled binary is what `Build and Run
     * with Vela` is for.
     *
     * A configuration that got this far without a file yields no state at all,
     * because there is nothing to interpret.  A missing *compiler* is different:
     * that is stated in the console, by the state below, so the user sees why
     * rather than watching an empty window.
     */
    override fun getState(
        executor: Executor,
        environment: ExecutionEnvironment,
    ): RunProfileState? {
        val raw = options.filePath?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val file = File(FileUtil.toSystemDependentName(raw))
        val configured = options.compilerPath?.trim().orEmpty()
        val found = configured.ifEmpty { VelaCompiler.locate(project) }
        return VelaRunState(
            environment = environment,
            file = file,
            arguments = ParametersListUtil.parse(options.programArguments.orEmpty()),
            compiler = found?.let { File(FileUtil.toSystemDependentName(it)) },
            missingCompilerMessage =
                "Vela: cannot find the compiler, so this file cannot be run. Set its "
                    + "path in this run configuration or in "
                    + "Settings | Languages & Frameworks | Vela, or set VELA_VM.",
        )
    }
}

/**
 * The Settings page the Run/Debug dialog shows: three fields, built in code.
 *
 * There is no `.form` file anywhere in this plugin, so this is a `JPanel` laid
 * out by hand.  `GridBagLayout` rather than a box layout because the *fields*
 * have to be the same width and grow with the dialog while the labels do not —
 * which is exactly what `weightx` on the second column says.
 */
class VelaRunConfigurationEditor(private val project: Project) :
    SettingsEditor<VelaRunConfiguration>() {

    private val filePath = TextFieldWithBrowseButton()
    private val compilerPath = TextFieldWithBrowseButton()
    private val arguments = JTextField()
    private val panel = JPanel(GridBagLayout())

    init {
        // A chooser can select one file; the descriptor is what tells it that,
        // and passing the project is what lets it remember where the user was.
        // `singleFile()`/`singleFileOrDir()`, not the older
        // `createSingle*Descriptor()` spellings: those are deprecated throughout
        // 262 and the deprecation is the platform saying which one it maintains.
        filePath.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile())
        // The compiler may be `vm.exe` or a bare `vm`; `singleFileOrDir` is the
        // closest of the maintained descriptors and imposes no type filter,
        // which is what a file with no extension on other platforms needs.
        compilerPath.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFileOrDir())

        var row = 0
        row = addRow(row, "Vela file:", filePath)
        row = addRow(row, "Compiler (vm.exe):", compilerPath)
        addRow(row, "Program arguments:", arguments)

        // `$VELA_VM` is escaped: an unescaped `$` would be Kotlin string
        // interpolation looking for a variable of that name, which is exactly
        // the kind of mistake that only shows up at compile time.
        val hint = JLabel(
            "<html>Left empty, the compiler is taken from "
                + "Settings | Languages &amp; Frameworks | Vela, then "
                + "<code>\$VELA_VM</code>, then "
                + "<code>&lt;project&gt;/selfhost/build/vm.exe</code>, then "
                + "<code>PATH</code>.</html>"
        )
        val hintConstraints = GridBagConstraints()
        hintConstraints.gridx = 0
        hintConstraints.gridy = row + 1
        hintConstraints.gridwidth = 2
        hintConstraints.weightx = 1.0
        hintConstraints.fill = GridBagConstraints.HORIZONTAL
        hintConstraints.anchor = GridBagConstraints.NORTHWEST
        hintConstraints.insets = Insets(8, 0, 0, 0)
        panel.add(hint, hintConstraints)
    }

    private fun addRow(row: Int, label: String, field: JComponent): Int {
        val labelConstraints = GridBagConstraints()
        labelConstraints.gridx = 0
        labelConstraints.gridy = row
        labelConstraints.anchor = GridBagConstraints.WEST
        labelConstraints.insets = Insets(4, 0, 4, 8)
        panel.add(JLabel(label), labelConstraints)

        val fieldConstraints = GridBagConstraints()
        fieldConstraints.gridx = 1
        fieldConstraints.gridy = row
        fieldConstraints.weightx = 1.0
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL
        fieldConstraints.insets = Insets(4, 0, 4, 0)
        panel.add(field, fieldConstraints)
        return row + 1
    }

    override fun resetEditorFrom(configuration: VelaRunConfiguration) {
        filePath.text = configuration.getFilePath().orEmpty()
        compilerPath.text = configuration.getCompilerPath().orEmpty()
        arguments.text = configuration.getProgramArguments().orEmpty()
    }

    override fun applyEditorTo(configuration: VelaRunConfiguration) {
        // An empty compiler path is stored as empty rather than filled in with
        // the path the plugin just resolved: blank means "ask the plugin to find
        // one", and writing the answer down would freeze a path the user never
        // chose and that goes stale the moment the setting changes.
        configuration.setFilePath(filePath.text.trim())
        configuration.setCompilerPath(compilerPath.text.trim())
        configuration.setProgramArguments(arguments.text.trim())
    }

    override fun createEditor(): JComponent = panel
}

/**
 * One Vela run: `vm.exe run <file>`, interpreted, in one console.
 *
 * There is exactly one child process.  An earlier version of this class built
 * the file first and then started the `.exe` the compiler wrote, which needed a
 * C compiler, MSVC's environment, an include directory resolved correctly and
 * write access beside the user's source — and it produced nothing on screen until
 * that finished.  Interpreting needs none of it.
 *
 * What the console shows is therefore the *interpreter's* output, and the
 * interpreter is slower than a compiled binary (the corpus's `run` cases measure
 * both; the README puts it at roughly 76x on a loop MSVC cannot vectorise).  That
 * is the right trade for a Run button — it always works, it starts immediately,
 * and it writes nothing next to the program — and `Build and Run with Vela` is
 * the path that compiles.
 *
 * No console is created here, and that is the point.  The console the user looks
 * at is the one the *platform* creates for the handler this method returns; a
 * console built here would be a private one, never added to a content
 * descriptor, and everything written into it would go somewhere nobody can see.
 * So nothing prints anywhere directly — `VelaProcessHandler` forwards output into
 * its own visible stream with `notifyTextAvailable`, the same mechanism
 * `OSProcessHandler` uses for a normal process.
 */
private class VelaRunState(
    environment: ExecutionEnvironment,
    private val file: File,
    private val arguments: List<String>,
    /** Null when no compiler could be found.  Not an early `return null` from
     *  `getState`: a state that says so in the console is the only way the user
     *  learns *why* the window is empty, and the platform gives no console to a
     *  state that was never produced. */
    private val compiler: File?,
    private val missingCompilerMessage: String,
) : CommandLineState(environment) {

    /**
     * Called on a background thread by the platform, which is the only reason
     * starting a process here is allowed: the one thing this method must not do
     * is block the EDT, and it does not — it hands back a handler immediately and
     * the child starts when the platform starts listening.
     */
    override fun startProcess(): ProcessHandler = VelaProcessHandler(
        command = commandLine(),
        failure = if (compiler == null) missingCompilerMessage else null,
    )

    /**
     * The exact line to reproduce what the Run button did, in a terminal.
     *
     * The interpreter is asked to run the file in place.  The working directory is
     * the file's own directory because that is the directory a program that opens
     * a data file beside itself expects, and the one the user would have been in.
     * No runtime include directory is passed: nothing here compiles, so there is
     * nothing for `-I` to find.
     */
    private fun commandLine(): GeneralCommandLine? {
        val executable = compiler ?: return null
        return GeneralCommandLine(executable.absolutePath, "run", file.absolutePath)
            .withWorkDirectory(file.parentFile)
            .withParameters(arguments)
    }
}

/**
 * The lifecycle the Run toolwindow sees: one interpreted run.
 *
 * There is no composite here any more.  With a single child there is no
 * sequencing to own, no second process to start on the first one's success, and
 * no build status that must not be mistaken for an exit status — so this class
 * exists for the three things that still need doing, and does nothing else:
 *
 *  * the command line is written to the console first, before the child starts,
 *    so the window always opens with the thing that was actually run;
 *  * the child's text is *forwarded* rather than printed — every event is
 *    re-emitted on this handler with `notifyTextAvailable`, so the text travels
 *    the platform's normal route (this handler's listeners, one of which is the
 *    visible console) and is re-labelled with `this`.  A console tracks live
 *    processes by handler identity, so a child forwarded as itself would register
 *    as a second, stranger process and the stop button would belong to neither;
 *  * the exit status is propagated unchanged through `notifyProcessTerminated`,
 *    so the toolwindow reports the interpreter's own verdict.
 *
 * The child starts from `startNotify` and not from the constructor: that is where
 * the platform begins listening, and a run that fails in a few milliseconds must
 * not be allowed to finish before anything is listening.
 */
private class VelaProcessHandler(
    private val command: GeneralCommandLine?,
    /** A reason this run cannot start at all, printed to the console instead of
     *  leaving the user with an empty window. */
    private val failure: String?,
) : ProcessHandler() {

    private var handler: OSProcessHandler? = null

    /** Terminal states are reached once, and two threads can try: the child's
     *  exit and a failed start both end here. */
    @Volatile
    private var finished = false

    @Volatile
    private var started = false

    /** The status the Run toolwindow reports, which is the child's own. */
    private var exitCode = -1

    override fun startNotify() {
        if (!started) {
            started = true
            startChild()
        }
        super.startNotify()
    }

    private fun startChild() {
        // Bound to a local val before the null test, so the compiler's smart cast
        // survives into the lambdas below without any doubt about a property that
        // could in principle be re-read.
        val command = this.command
        if (command == null) {
            // No compiler: said here, in the console, because this is the only
            // place the user can see it.  A failure that is *known* before
            // anything runs still has to be reported through a console the
            // platform created, which means reporting it from the handler.
            emit((failure ?: "Vela: this run cannot start.") + System.lineSeparator(),
                ProcessOutputType.STDERR)
            finish(1)
            return
        }
        // The command line first, in the system stream: the same line the user
        // would have typed, and the line to paste into a terminal when the run
        // fails for a reason the plugin cannot explain.
        emit(command.commandLineString + System.lineSeparator())
        try {
            val child = OSProcessHandler(command)
            handler = child
            child.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    emit(event.text, outputType)
                }

                override fun processTerminated(event: ProcessEvent) {
                    finish(event.exitCode)
                }
            })
            child.startNotify()
        } catch (e: ExecutionException) {
            // The compiler is there but cannot be started at all — a broken
            // image, a missing runtime, a directory where a file was expected.
            // Saying so beats a red "Failed" with no reason above it, and the
            // non-zero status is what the toolwindow keys off.
            emit(
                "Vela: could not start ${command.exePath}: ${e.message}"
                    + System.lineSeparator(),
                ProcessOutputType.STDERR,
            )
            finish(1)
        }
    }

    /**
     * Child output goes to this handler's own listeners, never to a console this
     * class made: the visible console is a listener here, so `notifyTextAvailable`
     * is what puts text in front of the user.
     *
     * The stream is carried through rather than flattened to stdout.  The
     * interpreter's diagnostics go to stderr and its program's output to stdout,
     * and the console colours the two differently — collapsing them would print a
     * runtime error in the same ink as a printed line.
     *
     * The type is chosen from the key itself and *not* with
     * `ProcessOutputType.fromKey`, which is the obvious-looking way and the wrong
     * one: `fromKey` is declared non-null, so a `?:` fallback after it can never
     * run, and measured against this platform it *throws*
     * `IllegalArgumentException: Unexpected key … Should be one of
     * ProcessOutputTypes` for a key it does not recognise — from inside this
     * console callback, i.e. in the one path whose whole job is to make output
     * visible.
     *
     * The predicates are the safe way, and they are *static* — `isStdout(Key)`,
     * not `outputType.isStdout()`, which does not exist (measured: the compiler
     * rejects it).  Measured: all three return `false` for a foreign key rather
     * than throwing, and each returns `true` for its own type, so an unrecognised
     * key degrades to plain stdout and the run continues.
     */
    private fun emit(text: String, outputType: Key<*>? = null) {
        val type: Key<*> = when {
            outputType == null -> ProcessOutputType.STDOUT
            ProcessOutputType.isStdout(outputType) -> ProcessOutputType.STDOUT
            ProcessOutputType.isStderr(outputType) -> ProcessOutputType.STDERR
            // `ProcessOutputType.isSystem(outputType)` is what this said, and it was the
            // one thing in this plugin that `since-build="253"` cannot have.  Measured:
            // `build-offline.ps1 -PlatformHome "D:\JetBrains\PyCharm 2025.3"` failed with
            // `VelaRunConfig.kt:569:31: error: unresolved reference 'isSystem'` -- the
            // method does not exist in PyCharm 2025.3, so the sources did NOT compile
            // against the platform `plugin.xml` claimed to support, and that claim was
            // false until this line changed.
            //
            // An identity comparison asks the same question and is the *safer* of the two
            // shapes the KDoc above complains about: it cannot throw for a foreign key the
            // way `fromKey` does, and `ProcessOutputType.SYSTEM` itself exists on both
            // platforms -- it is the unresolved *method*, not the constant, that 253 lacks.
            outputType === ProcessOutputType.SYSTEM -> ProcessOutputType.SYSTEM
            // A key from some future or third-party stream, not one of these:
            // plain stdout is the honest default and loses nothing but colour.
            else -> ProcessOutputType.STDOUT
        }
        notifyTextAvailable(text, type)
    }

    /** The one terminal transition. */
    private fun finish(code: Int) {
        synchronized(this) {
            if (finished) return
            finished = true
            exitCode = code
        }
        notifyProcessTerminated(code)
    }

    override fun destroyProcessImpl() {
        handler?.let { if (!it.isProcessTerminated) it.destroyProcess() }
        finish(-1)
    }

    /** The console stays attached after the stop button, so the interpreter's
     *  output and its diagnostics do not vanish with the process. */
    override fun detachIsDefault(): Boolean = false

    override fun detachProcessImpl() {
        notifyProcessDetached()
    }

    override fun isSilentlyDestroyOnClose(): Boolean = false

    /**
     * The parent's implementation throws `UnsupportedOperationException`, and the
     * console asks for this stream as soon as it is wired to this handler — an
     * exception thrown from a console callback is not a failure anything recovers
     * from.
     *
     * Nothing here needs the user's keystrokes: `vm.exe run` takes a file and the
     * programs the interpreter runs are not interactive.  So the stream is the
     * child's while it is running, and simply absent when it is not.
     */
    override fun getProcessInput(): OutputStream? {
        return try {
            handler?.getProcessInput()
        } catch (e: UnsupportedOperationException) {
            null
        }
    }

    /** Null until the child has exited: there is no earlier exit status to
     *  report, and inventing one would make the toolwindow lie. */
    override fun getExitCode(): Int? = if (finished) exitCode else null
}
