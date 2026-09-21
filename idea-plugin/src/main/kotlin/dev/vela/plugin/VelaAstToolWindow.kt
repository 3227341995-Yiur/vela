package dev.vela.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.messages.MessageBusConnection
import java.io.File
import javax.swing.JComponent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeNode

/**
 * The "Vela AST" tool window: what the *compiler* says this file is.
 *
 * Every other part of this plugin is careful to answer only questions about
 * characters.  This window is where the other kind of question is asked — "what
 * is this program's syntax tree" — and it is answered by the only thing entitled
 * to answer it.  The window renders `vm.exe parse <file>` verbatim: the same
 * nodes, in the same order, with the same `key=value` pairs.  When the compiler
 * refuses the file its diagnostic text is shown as the compiler wrote it,
 * because a plugin that paraphrased a diagnostic would be a second, worse
 * compiler.
 *
 * TWO MODES, AND WHY THE SECOND ONE IS NOT REDUNDANT
 *
 * `vm.exe parse` reads the file **on disk**.  While someone is typing, the file on
 * disk is older than the buffer, so the compiler's answer describes text the user
 * has already changed -- and a file that has never been saved has no on-disk form
 * at all.  The second mode answers the question the reader is actually asking
 * ("what does the editor think this is, right now") by running this plugin's own
 * parser over the buffer's text and printing it through [VelaSyntaxDump], which is
 * a transcription of the compiler's own `d_node` / `d_body` / `d_chain` printer.
 *
 * Both modes print the *same* format, which is the only reason the comparison is
 * worth anything: the differential harness in `ast-diff.ps1` is that same
 * comparison run over the whole corpus instead of over one file, and it is what
 * makes the plugin's tree safe to show beside the compiler's.
 *
 * The dump carries no line numbers and no byte offsets, so nothing here can offer
 * navigation or editor highlighting: the honest thing to do is show the text.
 */

class VelaAstToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = VelaAstPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.content, "", false)
        // The panel's message-bus connection lives exactly as long as the tool
        // window's content: registering it there is what keeps a closed tool
        // window from leaving a listener subscribed to the project.
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * The panel: a tree, a Refresh action, and a placeholder that always explains
 * itself.
 *
 * The compiler is *never* asked per keystroke.  It is a process, and a process per
 * character would be a `vm.exe` for every letter typed, so the only two things
 * that trigger a run are the editor's file selection changing and the user
 * pressing Refresh.  Nothing else in this class touches the compiler.
 */
private class VelaAstPanel(private val project: Project) : Disposable {

    private val model = DefaultTreeModel(DefaultMutableTreeNode(NOTHING_YET))
    private val tree = SimpleTree(model)

    /** Ties this panel's listener to the panel's own lifetime. */
    private val bus: MessageBusConnection = project.messageBus.connect(this)

    /**
     * Which of the two answers the window is showing.
     *
     * There is exactly one view and exactly one mode, rather than two panes that
     * race: two independent refreshes would both write the same tree model and the
     * slower one would win, so the window would show a different mode than the one
     * the user last asked for.  Selecting a file re-runs whichever mode is active.
     */
    private enum class VelaAstMode { COMPILER, LIVE }

    private var mode = VelaAstMode.COMPILER

    /**
     * Bumped by every refresh.  A slow run whose file is no longer the selected
     * one is dropped instead of replacing a newer tree; without this, a stale
     * answer could land after a fresh one and describe the wrong file.
     */
    private var generation = 0

    /**
     * The tool window's content: a toolbar over a scrolled tree, which is the
     * platform's standard shape and the reason `SimpleToolWindowPanel` is used
     * rather than a hand-built panel with a `BorderLayout`.
     */
    val content: SimpleToolWindowPanel = SimpleToolWindowPanel(true, true).apply {
        setContent(ScrollPaneFactory.createScrollPane(tree))
        setToolbar(toolbar())
    }

    init {
        // The compiler's dump starts at the module, and that root is a real node:
        // hiding it would present `block` as if it were the whole program.
        tree.isRootVisible = true
        tree.showsRootHandles = true

        // Follow the file the user is editing.  `selectionChanged` is the event
        // that matters: it fires when a different file becomes selected, which is
        // exactly when the tree on screen stopped describing the right file.
        bus.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    // Whichever mode is active follows the file the user is editing:
                    // the compiler dump describes what is on disk, the live dump what
                    // is in the buffer, and selecting another file invalidates both.
                    refresh()
                }
            } as FileEditorManagerListener,
        )

        // The window may be opened long after the file was, and a first refresh
        // deferred to the EDT means that starting the IDE with the window closed
        // costs no compiler run at all.
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) refresh()
        }, ModalityState.any())
    }

    private fun toolbar(): JComponent {
        val group = DefaultActionGroup()
        group.add(RefreshAction())
        group.add(LiveParseAction())
        val bar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        bar.targetComponent = tree
        return bar.component
    }

    private inner class RefreshAction : AnAction() {
        init {
            templatePresentation.text = "Compiler (vm.exe parse)"
            templatePresentation.description =
                "Run `vm.exe parse` on the file open in the editor and show what it prints"
        }

        // Constructing and updating an action presentation is UI work, so it
        // belongs on the EDT; the work itself only starts a task there.
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            mode = VelaAstMode.COMPILER
            refresh()
        }
    }

    /**
     * The other mode: parse the *buffer* with this plugin's own parser.
     *
     * This is the difference that matters while typing.  `vm.exe parse` is handed a
     * path, so it can only ever describe the last saved bytes; this action is handed
     * the text the editor is holding, so a file with unsaved edits -- or one that has
     * never been saved -- still has a tree.  The output format is the compiler's, by
     * construction: it is [VelaSyntaxDump], the transcription of the compiler's own
     * printer that `ast-diff.ps1` holds to `vm.exe parse` across the corpus.
     */
    private inner class LiveParseAction : AnAction() {
        init {
            templatePresentation.text = "Live parse (editor buffer)"
            templatePresentation.description =
                "Parse the text in the editor right now, with this plugin's parser, " +
                    "and print it in the compiler's own tree format"
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            mode = VelaAstMode.LIVE
            refresh()
        }
    }

    // ------------------------------------------------------------------ refresh

    /**
     * Show the active mode's answer for what is open *now*.  Called from the EDT
     * only (the listener, the two actions, and the deferred first run all arrive
     * there); each branch starts a background task and returns immediately.
     */
    private fun refresh() {
        when (mode) {
            VelaAstMode.COMPILER -> refreshCompiler()
            VelaAstMode.LIVE -> refreshLive()
        }
    }

    /**
     * Look at what is open *now* and show the compiler's answer.  Called from the
     * EDT only (the listener, the actions, and the deferred first run all arrive
     * there); it starts a background task and returns immediately.
     */
    private fun refreshCompiler() {
        if (project.isDisposed) return
        val file = selectedVelaFile()
        if (file == null) {
            show(DefaultMutableTreeNode(NO_VELA_FILE))
            return
        }
        val compiler = VelaCompiler.locate(project)
        if (compiler == null) {
            show(DefaultMutableTreeNode(NO_COMPILER))
            return
        }
        val path = file.path
        val onDisk = File(path)
        if (!onDisk.isFile) {
            // A file in a non-local filesystem (a remote host, a jar) has no path
            // the compiler could open.  Saying so is better than running `vm.exe`
            // on a name that does not exist and printing its error as if it were
            // about the program.
            show(DefaultMutableTreeNode("This file has no path the compiler can read:\n$path"))
            return
        }
        compile(onDisk, compiler)
    }

    /**
     * The other mode: parse the *buffer* with this plugin's own parser.
     *
     * This is the difference that matters while typing.  `vm.exe parse` is handed a
     * path, so it can only ever describe the last saved bytes; this mode is handed the
     * text the editor is holding, so a file with unsaved edits -- or one that has never
     * been saved at all -- still has a tree.  The output format is the compiler's by
     * construction: it is [VelaSyntaxDump], the transcription of the compiler's own
     * `d_node`/`d_body`/`d_chain` printer that `ast-diff.ps1` holds to `vm.exe parse`
     * across the whole corpus.
     *
     * The text is read here, on the EDT, because document access belongs to it; the
     * parse and the dump are pure string work and go to a background thread, since a
     * large file would otherwise freeze the UI for as long as the parse takes.
     */
    private fun refreshLive() {
        if (project.isDisposed) return
        val file = selectedVelaFile()
        if (file == null) {
            show(DefaultMutableTreeNode(NO_VELA_FILE))
            return
        }
        val text = currentText(file)
        if (text == null) {
            show(DefaultMutableTreeNode("This file's text could not be read:\n${file.path}"))
            return
        }
        val mine = ++generation
        object : Task.Backgroundable(project, "Parsing ${file.name}", false) {
            private var dump = ""
            private var problems: List<VelaSyntaxProblem> = emptyList()
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    val tree = VelaSyntaxParser.parse(text)
                    dump = VelaSyntaxDump.dump(tree)
                    problems = tree.problems
                } catch (t: Throwable) {
                    // A parser that throws must not take the editor down with it: the
                    // failure is shown as what it is rather than as an empty window.
                    failure = t.toString()
                }
            }

            override fun onSuccess() {
                if (mine != generation) return
                val f = failure
                if (f != null) {
                    show(DefaultMutableTreeNode("The parser failed on ${file.name}:\n$f"))
                    return
                }
                applyLiveDump(file.name, dump, problems)
            }

            override fun onThrowable(error: Throwable) {
                if (mine != generation) return
                show(DefaultMutableTreeNode("The parser could not run:\n${error.message}"))
            }
        }.queue()
    }

    /**
     * The text the editor is holding.
     *
     * `FileDocumentManager` is asked first: it is the *document* that carries unsaved
     * edits, and a Vela file is very often being typed into while this window is open.
     * A file with no loaded document (open in a project pane but never shown in an
     * editor) falls back to its bytes, which is then the same text `vm.exe parse`
     * would have seen.
     */
    private fun currentText(file: VirtualFile): String? = try {
        FileDocumentManager.getInstance().getDocument(file)?.text
            ?: String(file.contentsToByteArray(), Charsets.UTF_8)
    } catch (t: Throwable) {
        null
    }

    /**
     * Render the live parse, and say plainly when the parser refused something.
     *
     * A refusal is not hidden: the problem list is put at the root, above the tree,
     * because the tree is still a real shape for a file that is still being typed and
     * the reader needs both facts.  (The compiler mode does the same, in the same
     * place, for the same reason.)
     */
    private fun applyLiveDump(name: String, dump: String, problems: List<VelaSyntaxProblem>) {
        val lines = parseDump(dump)
        if (lines.isEmpty()) {
            show(DefaultMutableTreeNode("This plugin's parser produced no tree for $name."))
            return
        }
        if (problems.isNotEmpty()) {
            val root = DefaultMutableTreeNode(
                "refused: ${problems.size} problem(s) in this plugin's parse of the buffer"
            )
            for (p in problems) {
                root.add(DefaultMutableTreeNode("${p.message}  [offset ${p.start}..${p.end}]"))
            }
            root.add(DefaultMutableTreeNode("--- the tree it built anyway ---"))
            for (line in dump.lines()) root.add(DefaultMutableTreeNode(line))
            show(root)
        } else {
            show(toTree(lines))
        }
    }

    private fun selectedVelaFile(): VirtualFile? {
        // The file *selected in the editor*, not merely open: the window follows
        // what the user is looking at, and a tab that is open but hidden is not it.
        val vf = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return null
        if (!isVelaFileName(vf.name)) return null
        return vf
    }

    /**
     * Run the compiler off the EDT — it is a process, and a process on the EDT is
     * the freeze the platform rightly complains about.  [generation] makes a slow
     * run harmless: if the selection has moved on meanwhile, its answer is dropped.
     */
    private fun compile(file: File, compiler: String) {
        val mine = ++generation
        object : Task.Backgroundable(project, "Parsing ${file.name}", false) {
            private var exitCode = -1
            private var output = ""

            override fun run(indicator: ProgressIndicator) {
                val result = VelaCompiler.run(compiler, "parse", file)
                exitCode = result.first
                output = result.second
            }

            override fun onSuccess() {
                if (mine != generation) return
                applyDump(file, exitCode, output)
            }

            override fun onThrowable(error: Throwable) {
                if (mine != generation) return
                show(DefaultMutableTreeNode("The compiler could not be run:\n${error.message}"))
            }
        }.queue()
    }

    /**
     * Turn one run into a tree.
     *
     * A refusal is shown verbatim.  A partial dump *and* a refusal are both shown,
     * because the compiler prints the tree it managed to build before it noticed
     * the problem; discarding either half would hide something it said.
     */
    private fun applyDump(file: File, exitCode: Int, output: String) {
        val lines = parseDump(output)
        val diagnostics = VelaCompiler.parse(output)
        if (lines.isEmpty()) {
            val failure = if (output.isBlank()) {
                "It printed nothing and exited with status $exitCode."
            } else {
                output.trim()
            }
            show(
                DefaultMutableTreeNode(
                    "The compiler did not print a syntax tree for ${file.name}:\n\n$failure"
                )
            )
            return
        }
        if (diagnostics.isNotEmpty()) {
            // The dump is real, but the file is not a legal program.  The tree is
            // still worth showing — it is what the compiler made of the text — and
            // its complaint is put where it cannot be missed: at the root, labelled
            // as the compiler's own output.
            val first = diagnostics.first()
            val root = DefaultMutableTreeNode(
                "refused: ${first.message} (line ${first.line})"
            )
            root.add(DefaultMutableTreeNode("--- what the compiler printed ---"))
            for (line in output.lines()) root.add(DefaultMutableTreeNode(line))
            show(root)
        } else {
            show(toTree(lines))
        }
    }

    // -------------------------------------------------------------- dump parsing

    /**
     * The dump is `  ` × depth + `kind key=value...`, one node per line, with no
     * line numbers and no other punctuation.  Depth is counted from the leading
     * spaces and the tree is rebuilt from those depths alone: the parent of a line
     * is the nearest earlier line with a smaller depth.  Nothing else is inferred
     * — in particular no `key=value` is reinterpreted, because the whole point of
     * this window is to show what the compiler wrote.
     */
    private fun parseDump(output: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        for (raw in output.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            var depth = 0
            while (depth < line.length && line[depth] == ' ') depth++
            if (depth >= line.length) continue
            out.add(depth to line.substring(depth))
        }
        return out
    }

    private fun toTree(lines: List<Pair<Int, String>>): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode(lines.first().second)
        val stack = ArrayList<DefaultMutableTreeNode>()
        val depths = ArrayList<Int>()
        stack.add(root)
        depths.add(lines.first().first)
        for (i in 1 until lines.size) {
            val (depth, body) = lines[i]
            val node = DefaultMutableTreeNode(body)
            // Pop until the top of the stack is genuinely a parent of this line.
            // A line that jumps out past the first line's depth is attached to the
            // root rather than dropped: the text came from the compiler, and losing
            // a node it printed would misreport it.
            while (stack.size > 1 && depths.last() >= depth) {
                stack.removeAt(stack.size - 1)
                depths.removeAt(depths.size - 1)
            }
            stack.last().add(node)
            stack.add(node)
            depths.add(depth)
        }
        return root
    }

    // ------------------------------------------------------------------- display

    private fun show(root: DefaultMutableTreeNode) {
        model.setRoot(root)
        model.reload()
        // Everything expanded: the point of the window is to see the whole tree,
        // not to click it open one node at a time.  Every row's path is expanded
        // from the root down, so nested nodes come out expanded too.
        val treeRoot = tree.model.root as? TreeNode ?: return
        tree.expandPath(TreePath(treeRoot))
        for (row in 0 until tree.rowCount) {
            tree.expandPath(tree.getPathForRow(row))
        }
    }

    override fun dispose() {
        generation++
    }

    private companion object {
        const val NOTHING_YET =
            "The syntax tree for the file in the editor appears here.\n" +
                "Open a Vela file, or press one of the two refresh buttons:\n" +
                "  \"Compiler (vm.exe parse)\" -- what the compiler says about the file ON DISK\n" +
                "  \"Live parse (editor buffer)\" -- what this plugin's parser says about the text\n" +
                "                                  in the editor RIGHT NOW, unsaved edits included."

        const val NO_VELA_FILE =
            "No Vela file is open in the editor.\n" +
                "This window shows the syntax tree of a `.vel` or `.vela` file."

        const val NO_COMPILER =
            "The Vela compiler was not found.\n" +
                "Set its path in Settings | Languages & Frameworks | Vela, or set VELA_VM.\n" +
                "(The \"Live parse (editor buffer)\" button needs no compiler.)"
    }
}
