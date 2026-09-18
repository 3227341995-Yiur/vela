package dev.vela.plugin

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager

/**
 * `New Vela File`.
 *
 * There is no `.form`, no template engine and no new-project wizard here, because
 * a Vela file is three lines of text and everything bigger would be machinery the
 * plugin does not need.  What the action must get right is *where* the file goes
 * and that the plugin does not claim to have created something it did not:
 *
 *   * the directory is the one the user is looking at (a project view selection,
 *     or the folder of the file in the editor), and there is no fallback to the
 *     project root that silently puts a file somewhere the user was not;
 *   * the extension is added, so `hello` and `hello.vel` produce the same file;
 *   * a name that is not a legal Vela identifier is refused with a reason rather
 *     than accepted and then refuted by the compiler a moment later;
 *   * the file is written to disk before it is opened, so an editor is never
 *     showing content that does not exist.
 *
 * The action is disabled unless there is a project and a target directory that is
 * a writable local root — the VFS knows about read-only mounts and remote roots,
 * and a plain existence check does not.
 */
class VelaNewFileAction : AnAction() {

    /** The skeleton is the smallest legal Vela program this language's examples use. */
    private val skeleton = """
        def main() -> None {
            print("hello from Vela")
        }
    """.trimIndent() + "\n"

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && targetDirectory(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val directory = targetDirectory(e) ?: return
        val velaDirectory = directory.virtualFile

        val name = Messages.showInputDialog(
            project,
            "Create <name>.vel in ${directory.name}:",
            "New Vela File",
            Messages.getQuestionIcon(),
            "",
            object : InputValidator {
                override fun checkInput(inputString: String?): Boolean = true
                override fun canClose(inputString: String?): Boolean =
                    validationError(inputString) == null
            },
        )
        // Cancelled: no file, no message.  An empty name is a validation failure.
        if (name == null) return
        validationError(name)?.let {
            Messages.showErrorDialog(project, it, "New Vela File")
            return
        }

        val fileName = withVelaExtension(name)
        if (velaDirectory.findChild(fileName) != null) {
            Messages.showErrorDialog(
                project,
                "$fileName already exists in ${directory.name}.",
                "New Vela File",
            )
            return
        }

        val created = try {
            velaDirectory.createChildData(this, fileName)
        } catch (t: Throwable) {
            // The VFS refused — read-only root, a race with another writer, or a name
            // the platform itself rejects.  Saying so is the only honest outcome.
            Messages.showErrorDialog(
                project,
                "Could not create $fileName in ${directory.name}: ${t.message ?: t.toString()}",
                "New Vela File",
            )
            return
        }
        VfsUtil.saveText(created, skeleton)
        // Open it the way any file is opened, so the user sees exactly the file that
        // was written and can edit or undo the skeleton like ordinary text.
        FileEditorManager.getInstance(project).openFile(created, true)
    }

    /**
     * Where the file goes: the folder the user is looking at.
     *
     * A project view selection first (that is where "new file" is normally asked
     * for), then the folder of the file selected in the editor.  Null when neither
     * is a writable directory, which is what disables the action.
     */
    private fun targetDirectory(e: AnActionEvent): PsiDirectory? {
        val project = e.project ?: return null
        // The view's own directories are the selected ones, and the first that is
        // usable is the target; the platform has no "selected element" call on this
        // interface, so the editor's file is the second source instead.
        e.getData(LangDataKeys.IDE_VIEW)?.getDirectories()
            ?.firstOrNull { isUsable(it) }
            ?.let { return it }

        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        val folder = if (file.isDirectory) file else file.parent ?: return null
        if (!folder.isWritable) return null
        return PsiManager.getInstance(project).findDirectory(folder)?.takeIf { isUsable(it) }
    }

    private fun isUsable(directory: PsiDirectory?): Boolean {
        val vf = directory?.virtualFile ?: return false
        return vf.isValid && vf.isDirectory && vf.isWritable && vf.isInLocalFileSystem
    }

    /**
     * `hello` -> `hello.vel`, `hello.vel` -> `hello.vel`.  The extension is added
     * rather than demanded, because a user asking for "a new hello" should get the
     * file the language is spelled by.
     */
    private fun withVelaExtension(name: String): String {
        val trimmed = name.trim()
        return if (isVelaFileName(trimmed)) {
            trimmed
        } else {
            "$trimmed.${VelaFileType.EXTENSIONS.first()}"
        }
    }

    /**
     * Why this is not a usable file name, or null when it is.
     *
     * The rule is Vela's own: a name is a letter or `_` followed by letters, digits
     * and `_`, which is what `VelaLexer.isNameStart`/`isNamePart` accept — so a
     * name accepted here is one the file can declare and call.
     */
    private fun validationError(input: String?): String? {
        val name = input?.trim() ?: ""
        if (name.isEmpty()) return "Enter a name."
        val bare = VelaFileType.EXTENSIONS.firstOrNull { name.endsWith(".$it") }
            ?.let { name.dropLast(it.length + 1) }
            ?: name
        if (bare.isEmpty()) return "Enter a name before the extension."
        if (bare.any { it == '/' || it == '\\' || it.isWhitespace() }) {
            return "A file name cannot contain spaces or path separators."
        }
        if (!isNamePart(bare.first())) return "A Vela name starts with a letter or `_`."
        if (bare.any { !isNamePart(it) }) {
            return "A Vela name uses letters, digits and `_` only."
        }
        // Windows reserves these; the VFS refuses them too, but a name the user can
        // understand is better than a system error.
        if (bare.uppercase() in RESERVED) return "`$bare` is a reserved name on this system."
        return null
    }

    private companion object {
        val RESERVED = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        )
    }
}
