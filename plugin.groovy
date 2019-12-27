import com.intellij.concurrency.JobScheduler
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange

import static com.intellij.notification.NotificationType.*
import static com.intellij.openapi.editor.markup.HighlighterLayer.LAST
import static com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE
import static com.intellij.ui.JBColor.*
import static java.util.concurrent.TimeUnit.MILLISECONDS
import static liveplugin.PluginUtil.*
import static liveplugin.PluginUtil.getGlobalVar

def tidalBootScriptPath = "$pluginPath/BootTidal.hs"
def ghciPath = "/usr/local/bin/ghci"

class TidalProcess {
	private final String tidalBootFilePath
	private final String ghciPath
	private Process ghciProcess
	private Writer ghciWriter

	TidalProcess(String tidalBootFilePath, String ghciPath) {
		this.tidalBootFilePath = tidalBootFilePath
		this.ghciPath = ghciPath
	}

	def start(Closure onStdout, Closure onStderr, Closure onException) {
		ghciProcess = Runtime.getRuntime().exec(ghciPath)
		ghciWriter = ghciProcess.outputStream.newWriter()

		def outputConsumingThread = new Thread(new Runnable() {
			@Override void run() {
				try {
					def stdoutStream = ghciProcess.inputStream.newReader()
					def stderrStream = ghciProcess.errorStream.newReader()
					while (isRunning()) {
						def stdout = readLines(stdoutStream)
						if (!stdout.isEmpty()) onStdout(stdout)

						def stderr = readLines(stderrStream)
						if (!stderr.isEmpty()) onStderr(stderr)

						Thread.sleep(200)
					}
				} catch (Exception e) {
					onException(e)
				}
			}
		})
		outputConsumingThread.start()

		new File(tidalBootFilePath).eachLine { line -> writeLine(line) }

		this
	}

	private static String readLines(BufferedReader inputStream) {
		def lines = ""
		while (inputStream.ready()) {
			def c = inputStream.read() as char
			lines += c
		}
		lines
	}

	def writeLine(String line) {
		try {
			ghciWriter.write(line + "\n")
			ghciWriter.flush()
		} catch (Exception e) {
			onException(e)
		}
	}

	def isRunning() {
		ghciProcess != null && ghciProcess.alive
	}

	def stop() {
		ghciWriter.close()
		ghciProcess.destroyForcibly()
		this
	}
}

def showMessage(String message) {
	message = message.replace("Prelude>", "").trim()
	if (!message.isEmpty()) show(message, "Tidal Cycles", INFORMATION, "Tidal Cycles")
}

def showError(String message) {
	message = message.replace("Prelude>", "").trim()
	if (!message.isEmpty()) show(message, "Tidal Cycles", WARNING, "Tidal Cycles")
}

def showException(e) {
	show(e, "Tidal Cycles", ERROR, "Tidal Cycles")
}

registerAction("StartOrStopTidal") {
	changeGlobalVar("tidalProcess") { process ->
		if (process?.isRunning()) {
			showMessage("Stopped tidal")
			process.stop()
		} else {
			showMessage("Started tidal")
			def onInput = { showMessage(it) }
			def onError = { showError(it) }
			def onException = { showException(it) }
			new TidalProcess(tidalBootScriptPath, ghciPath).start(onInput, onError, onException)
		}
	}
}

def isBlank(String s) {
	s == null || s.isEmpty() || s.isAllWhitespace()
}

def textFrom(TextRange textRange, Editor editor) {
	def text = editor.document.getText(textRange).trim()
	if (isBlank(text)) null else text
}

def selectionRange(Editor editor) {
	new TextRange(editor.selectionModel.selectionStart, editor.selectionModel.selectionEnd)
}

def currentLineRange(Editor editor) {
	def line = editor.caretModel.logicalPosition.line
	new TextRange(editor.document.getLineStartOffset(line), editor.document.getLineEndOffset(line))
}

def currentParagraphRange(Editor editor) {
	def currentLine = editor.caretModel.logicalPosition.line
	def lines = editor.document.text.split("\n", -1)
	def lastLine = lines.size() - 1
	if (lines[currentLine].isEmpty()) return new TextRange(0, 0)

	def fromLine = (0..currentLine).reverse().find { lines[it].isEmpty() } ?: 0
	def toLine = (currentLine..lastLine).find { lines[it].isEmpty() } ?: lastLine

	if (lines[fromLine].isEmpty() && fromLine < lastLine) fromLine++

	new TextRange(editor.document.getLineStartOffset(fromLine), editor.document.getLineEndOffset(toLine))
}

def highlight(TextRange textRange, Editor editor) {
	def textAttributes = new TextAttributes().with {
		it.setBackgroundColor(lightGray)
		it
	}
	def highlighter = editor.markupModel.addRangeHighlighter(textRange.startOffset, textRange.endOffset, LAST, textAttributes, EXACT_RANGE)
	JobScheduler.scheduler.schedule({
		invokeOnEDT { editor.markupModel.removeHighlighter(highlighter) }
	}, 200, MILLISECONDS)
}

registerAction("SendLineToTidal", "ctrl L") { AnActionEvent event ->
	def editor = currentEditorIn(event.project)

	def textRange = selectionRange(editor)
	def text = textFrom(textRange, editor)
	if (text == null) {
		textRange = currentLineRange(editor)
		text = textFrom(textRange, editor)
	}
	if (isBlank(text)) return

	highlight(textRange, editor)

	def tidal = getGlobalVar("tidalProcess")
	text = text.replace("\t", "  ").replaceAll("\n +", "\r  ")
	text.split("\n").each {
		tidal?.writeLine(it)
	}   
}

registerAction("SendParagraphToTidal", "ctrl K") { AnActionEvent event ->
	def editor = currentEditorIn(event.project)

	def textRange = currentParagraphRange(editor)
	text = textFrom(textRange, editor)
	if (isBlank(text)) return

	highlight(textRange, editor)

	def tidal = getGlobalVar("tidalProcess")
	text = text.replace("\t", "  ").replaceAll("\n +", "\r  ")
	text.split("\n").each {
		tidal?.writeLine(it)
	}   
}

registerAction("HushTidal", "ctrl H") { AnActionEvent event ->
	getGlobalVar("tidalProcess")?.writeLine("hush")
	showMessage("Hushed 🤫")
}


if (!isIdeStartup) showMessage("Reloaded plugin")
