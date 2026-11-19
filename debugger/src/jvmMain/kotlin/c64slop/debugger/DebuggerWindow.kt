// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.debugger

import c64slop.cpu.DisassembledInstruction
import c64slop.cpu.Disassembler
import c64slop.cpu.StepKind
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.JToolBar
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.text.DefaultCaret

/**
 * A debugger window for any 6502 machine: the code around the PC with its
 * breakpoints, the registers, the stack, the breakpoint list and a memory
 * dump, with buttons to run, pause and step. It shows [DebugSnapshot]s from
 * [target] and sends it commands; it never touches the machine itself.
 *
 * @param title the window title.
 * @param labels names for addresses, such as a machine's OS entry points: the
 *   code view shows `JSR OSWRCH` for `JSR $FFEE`, and Go to accepts the name.
 * @param configureFrame a last word on the frame, such as setting its icon.
 *
 * Keys: F5 continue, F6 pause, F7 step into, F8 step over, Shift+F8 step out,
 * F9 toggle the breakpoint on the selected line. Click the left edge of a line,
 * or double-click it, to toggle its breakpoint.
 */
class DebuggerWindow(
    private val target: DebugTarget,
    title: String = "Debugger",
    private val labels: (address: Int) -> String? = { null },
    configureFrame: (JFrame) -> Unit = {},
) {
    private val frame = JFrame(title)
    private val mono = Font(Font.MONOSPACED, Font.PLAIN, 13)

    private val codeModel = DefaultListModel<DisassembledInstruction>()
    private val code = JList(codeModel)
    private val followPc = JToggleButton("Follow PC", true)
    private val gotoField = JTextField(6)
    private val status = JLabel(" ")
    private val registers = textArea(rows = 6, columns = 30)
    private val stack = textArea(rows = 8, columns = 30)
    private val breakpointModel = DefaultListModel<Int>()
    private val breakpointList = JList(breakpointModel)
    private val memoryField = JTextField("0000", 6)
    private val memoryText = textArea(rows = MEMORY_ROWS, columns = 76)

    private val continueButton = button("Continue", "F5") { target.debugContinue() }
    private val pauseButton = button("Pause", "F6") { target.debugPause() }
    private val stepIntoButton = button("Step Into", "F7") { target.debugStepInto() }
    private val stepOverButton = button("Step Over", "F8") { target.debugStepOver() }
    private val stepOutButton = button("Step Out", "Shift+F8") { target.debugStepOut() }

    private var snapshot: DebugSnapshot? = null

    /** Every address [labels] names, by name in capitals, for Go to. */
    private val named: Map<String, Int> =
        (0..0xFFFF).mapNotNull { a -> labels(a)?.let { it.uppercase() to a } }.toMap()

    /** Where the code view starts when it isn't following the PC. */
    private var viewAddress = 0
    private var memoryAddress = 0

    /** The address the code view last scrolled to, so it scrolls only when that changes. */
    private var shownCenter = -1

    private val latest = AtomicReference<DebugSnapshot?>()
    private val updateScheduled = AtomicBoolean(false)

    init {
        frame.defaultCloseOperation = WindowConstants.HIDE_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            // No snapshots while nobody looks at them; breakpoints stay set.
            override fun windowClosing(e: WindowEvent) = target.watch(null)
        })

        val toolbar = JToolBar().apply {
            isFloatable = false
            for (b in listOf(continueButton, pauseButton, stepIntoButton, stepOverButton, stepOutButton)) add(b)
            addSeparator()
            add(JLabel("Go to "))
            gotoField.maximumSize = gotoField.preferredSize
            gotoField.toolTipText = "A hex address, as in 3FB6, or a name the machine gives one"
            gotoField.addActionListener { goTo(gotoField.text) }
            add(gotoField)
            followPc.toolTipText = "Keep the code view on the instruction about to run"
            followPc.addActionListener { refresh() }
            add(followPc)
            addSeparator()
            // A fixed width, so a longer stop reason doesn't relayout the window.
            status.preferredSize = Dimension(320, status.preferredSize.height)
            status.maximumSize = status.preferredSize
            add(status)
        }

        code.font = mono
        // Fixed cell sizes: the list's preferred size never depends on what it shows.
        code.fixedCellHeight = code.getFontMetrics(mono).height + 2
        code.fixedCellWidth = code.getFontMetrics(mono).charWidth('0') * CODE_COLUMNS + GUTTER_WIDTH
        code.selectionMode = ListSelectionModel.SINGLE_SELECTION
        code.cellRenderer = CodeRenderer()
        code.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = code.locationToIndex(e.point).takeIf { it >= 0 } ?: return
                if (!code.getCellBounds(row, row).contains(e.point)) return
                if (!SwingUtilities.isLeftMouseButton(e)) return
                // One click in the gutter, or a double-click on the text. A double-click
                // in the gutter is two clicks there, and mustn't toggle twice.
                val inGutter = e.x < GUTTER_WIDTH
                if (inGutter && e.clickCount == 1 || !inGutter && e.clickCount == 2) {
                    target.toggleBreakpoint(codeModel[row].address)
                }
            }

            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)

            private fun popup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = code.locationToIndex(e.point).takeIf { it >= 0 } ?: return
                code.selectedIndex = row
                codeMenu(codeModel[row]).show(code, e.x, e.y)
            }
        })

        breakpointList.font = mono
        breakpointList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, selected: Boolean, focus: Boolean,
            ): Component {
                val address = value as Int
                val text = snapshot?.let { "%04X  %s".format(address, disassembler(it).instruction(address).text) }
                    ?: "%04X".format(address)
                return super.getListCellRendererComponent(list, text, index, selected, focus)
            }
        }
        breakpointList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) breakpointList.selectedValue?.let { showCode(it) }
            }
        })
        breakpointList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "remove")
        breakpointList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "remove")
        breakpointList.actionMap.put("remove", action { removeSelectedBreakpoints() })

        val breakpointButtons = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JButton("Remove").apply { addActionListener { removeSelectedBreakpoints() } })
            add(JButton("Clear All").apply { addActionListener { target.clearBreakpoints() } })
            add(Box.createHorizontalGlue())
        }
        val breakpointPanel = JPanel(BorderLayout()).apply {
            add(JScrollPane(breakpointList).apply { preferredSize = Dimension(260, 120) }, BorderLayout.CENTER)
            add(breakpointButtons, BorderLayout.SOUTH)
        }

        val side = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(titled("Registers", registers))
            add(titled("Stack", JScrollPane(stack)))
            add(titled("Breakpoints", breakpointPanel))
        }

        memoryField.addActionListener {
            parseAddress(memoryField.text)?.let {
                memoryAddress = it
                refresh()
            }
        }
        memoryText.addMouseWheelListener { e ->
            memoryAddress = (memoryAddress + 16 * e.wheelRotation) and 0xFFFF
            refresh()
        }
        val memoryPanel = JPanel(BorderLayout()).apply {
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(JLabel("Address "))
                memoryField.maximumSize = memoryField.preferredSize
                add(memoryField)
                add(Box.createHorizontalGlue())
            }, BorderLayout.NORTH)
            add(memoryText, BorderLayout.CENTER)
        }

        // Continuous layout: the panes follow the divider as it is dragged, rather than a
        // drag outline that the snapshots' repaints would draw over.
        val top = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, true, JScrollPane(code), side).apply { resizeWeight = 1.0 }
        val main = JSplitPane(JSplitPane.VERTICAL_SPLIT, true, top, titled("Memory", memoryPanel)).apply { resizeWeight = 1.0 }

        frame.contentPane.add(toolbar, BorderLayout.NORTH)
        frame.contentPane.add(main, BorderLayout.CENTER)
        bindKeys(frame.rootPane)
        frame.preferredSize = Dimension(980, 760)
        frame.pack()
        configureFrame(frame)
        updateButtons(paused = false)
    }

    /** Shows the window and starts receiving snapshots. */
    fun show() {
        target.watch(::receive)
        if (!frame.isVisible) frame.isVisible = true
        frame.toFront()
    }

    /** Called on the emulation thread; shows only the newest snapshot. */
    private fun receive(next: DebugSnapshot) {
        latest.set(next)
        if (updateScheduled.compareAndSet(false, true)) {
            SwingUtilities.invokeLater {
                updateScheduled.set(false)
                latest.getAndSet(null)?.let { update(it) }
            }
        }
    }

    private fun update(next: DebugSnapshot) {
        val stopped = snapshot?.paused != true && next.paused
        snapshot = next
        if (stopped) {
            followPc.isSelected = true
            shownCenter = -1
        }
        updateButtons(next.paused)
        refresh()
    }

    private fun refresh() {
        val s = snapshot ?: return
        val state = s.state
        status.setTextIfChanged(s.stopReason ?: "Running")

        // Code.
        val pc = state.programCounter
        val center = if (followPc.isSelected) pc else viewAddress
        val selected = code.selectedValue?.address
        val disassembler = disassembler(s)
        val rows = disassembler.before(center, ROWS_BEFORE) + disassembler.instructions(center, ROWS_AFTER)
        if (codeModel.elements().toList() != rows) {
            codeModel.clear()
            codeModel.addAll(rows)
        }
        rows.indexOfFirst { it.address == selected }.takeIf { it >= 0 }?.let { code.selectedIndex = it }
        val centerRow = rows.indexOfFirst { it.address == center }
        if (centerRow >= 0 && center != shownCenter) {
            shownCenter = center
            // Leave a few lines of context above.
            code.ensureIndexIsVisible((centerRow + VISIBLE_ROWS / 2).coerceAtMost(rows.size - 1))
            code.ensureIndexIsVisible((centerRow - 4).coerceAtLeast(0))
        }

        // Registers.
        val last = when (s.lastStep) {
            StepKind.INSTRUCTION -> "instruction"
            StepKind.IRQ -> "took IRQ"
            StepKind.NMI -> "took NMI"
            StepKind.HALTED -> "halted"
        }
        registers.setTextIfChanged("""
            PC %04X   A %02X   X %02X   Y %02X
            SP %02X     P %02X   %s
            Last step  %s%s
            Cycles     %,d
            Frame      %,d
        """.trimIndent().format(
            pc, state.accumulator, state.x, state.y,
            state.stackPointer, state.status, state.flags,
            last, if (s.halted) " (JAM)" else "",
            s.cycles, s.frame,
        ))

        // Stack: what the next pulls would get, nearest first.
        stack.setTextIfChanged((state.stackPointer + 1..minOf(state.stackPointer + STACK_ROWS, 0xFF))
            .joinToString("\n") { "01%02X  %02X".format(it, s.peek(0x100 + it)) }
            .ifEmpty { "(empty)" })

        // Breakpoints.
        if (breakpointModel.elements().toList() != s.breakpoints) {
            breakpointModel.clear()
            breakpointModel.addAll(s.breakpoints)
        }

        // Memory.
        memoryText.setTextIfChanged((0 until MEMORY_ROWS).joinToString("\n") { row ->
            val address = (memoryAddress + 16 * row) and 0xFFFF
            val bytes = (0 until 16).map { s.peek(address + it) }
            "%04X  %s  %s".format(
                address,
                bytes.chunked(8).joinToString("  ") { half -> half.joinToString(" ") { "%02X".format(it) } },
                bytes.joinToString("") { if (it in 0x20..0x7E) it.toChar().toString() else "." },
            )
        })
        code.repaint()
    }

    private fun updateButtons(paused: Boolean) {
        continueButton.isEnabled = paused
        pauseButton.isEnabled = !paused
        stepIntoButton.isEnabled = paused
        stepOverButton.isEnabled = paused
        stepOutButton.isEnabled = paused
    }

    /** Shows the code at [address] and stops following the PC. */
    private fun showCode(address: Int) {
        viewAddress = address and 0xFFFF
        followPc.isSelected = false
        shownCenter = -1
        refresh()
        code.selectedIndex = codeModel.elements().toList().indexOfFirst { it.address == viewAddress }
    }

    private fun goTo(text: String) {
        val address = parseAddress(text)
        if (address == null) {
            status.text = "Not an address: $text"
            return
        }
        showCode(address)
        memoryAddress = address and 0xFFF0
        refresh()
    }

    private fun parseAddress(text: String): Int? {
        val trimmed = text.trim()
        named[trimmed.uppercase()]?.let { return it }
        return trimmed.removePrefix("$").removePrefix("&").removePrefix("0x")
            .toIntOrNull(16)?.takeIf { it in 0..0xFFFF }
    }

    private fun removeSelectedBreakpoints() {
        for (address in breakpointList.selectedValuesList) target.setBreakpoint(address, false)
    }

    private fun codeMenu(line: DisassembledInstruction) = JPopupMenu().apply {
        add(menuItem("Toggle Breakpoint") { target.toggleBreakpoint(line.address) })
        add(menuItem("Run to Here") { target.debugRunTo(line.address) }.apply {
            isEnabled = snapshot?.paused == true
        })
        line.target?.let { target ->
            add(menuItem("Show Code at %04X".format(target)) { showCode(target) })
            add(menuItem("Show Memory at %04X".format(target)) {
                memoryAddress = target and 0xFFF0
                refresh()
            })
        }
    }

    private fun bindKeys(root: JComponent) {
        fun bind(key: Int, modifiers: Int, name: String, run: () -> Unit) {
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, modifiers), name)
            root.actionMap.put(name, action(run))
        }
        bind(KeyEvent.VK_F5, 0, "continue") { if (continueButton.isEnabled) continueButton.doClick() }
        bind(KeyEvent.VK_F6, 0, "pause") { if (pauseButton.isEnabled) pauseButton.doClick() }
        bind(KeyEvent.VK_F7, 0, "stepInto") { if (stepIntoButton.isEnabled) stepIntoButton.doClick() }
        bind(KeyEvent.VK_F8, 0, "stepOver") { if (stepOverButton.isEnabled) stepOverButton.doClick() }
        bind(KeyEvent.VK_F8, InputEvent.SHIFT_DOWN_MASK, "stepOut") { if (stepOutButton.isEnabled) stepOutButton.doClick() }
        bind(KeyEvent.VK_F9, 0, "toggle") { code.selectedValue?.let { target.toggleBreakpoint(it.address) } }
    }

    private fun disassembler(s: DebugSnapshot) = Disassembler(s::peek, labels)

    private inner class CodeRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, selected: Boolean, focus: Boolean,
        ): Component {
            val line = value as DisassembledInstruction
            val s = snapshot
            val isPc = s != null && line.address == s.state.programCounter
            val isBreak = s != null && line.address in s.breakpoints
            val gutter = (if (isBreak) "●" else " ") + (if (isPc) "▶" else " ")
            val name = labels(line.address)?.let { "  ; $it" } ?: ""
            super.getListCellRendererComponent(list, "$gutter $line$name", index, selected, focus)
            if (isBreak && !selected) foreground = BREAK_COLOR
            if (isPc && !selected) {
                background = PC_BACKGROUND
                foreground = if (isBreak) BREAK_COLOR else Color.BLACK
                isOpaque = true
            }
            return this
        }
    }

    /** Rows and columns fix the preferred size, so new text never relayouts the window. */
    private fun textArea(rows: Int, columns: Int) = JTextArea(rows, columns).apply {
        font = mono
        isEditable = false
        // Setting the text mustn't move the caret, which would scroll the stack to its end.
        (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
    }

    /** Setting text revalidates and repaints, so do it only when the text differs. */
    private fun JTextArea.setTextIfChanged(value: String) { if (text != value) text = value }

    private fun JLabel.setTextIfChanged(value: String) { if (text != value) text = value }

    private fun titled(title: String, content: JComponent) = JPanel(BorderLayout()).apply {
        border = BorderFactory.createTitledBorder(title)
        add(content, BorderLayout.CENTER)
    }

    private fun button(title: String, key: String, run: () -> Unit) = JButton(title).apply {
        toolTipText = "$title ($key)"
        isFocusable = false
        addActionListener { run() }
    }

    private fun menuItem(title: String, run: () -> Unit) = JMenuItem(action(run)).apply { text = title }

    private fun action(run: () -> Unit) = object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent) = run()
    }

    private companion object {
        const val ROWS_BEFORE = 16
        const val ROWS_AFTER = 64
        const val VISIBLE_ROWS = 24
        const val STACK_ROWS = 32
        const val MEMORY_ROWS = 8
        const val GUTTER_WIDTH = 24
        const val CODE_COLUMNS = 48

        val BREAK_COLOR = Color(0xC0, 0x20, 0x20)
        val PC_BACKGROUND = Color(0xFF, 0xF0, 0xA0)
    }
}
