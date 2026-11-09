// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.desktop

import c64slop.c64.Disk
import c64slop.c64.KeyboardMatrix.Key
import c64slop.c64.Tape
import java.awt.BorderLayout
import java.awt.FileDialog
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.io.IOException
import javax.swing.AbstractAction
import javax.swing.ButtonGroup
import javax.swing.JCheckBoxMenuItem
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JRadioButtonMenuItem
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.system.exitProcess

/** The emulator window: the picture, the keyboard and the menus. Adapted from Beebslop's `EmulatorWindow`. */
class EmulatorWindow(roms: Roms, private val options: Options) : Emulator.Listener {
    /** The datasette's keys, as the Tape menu last set them. */
    private enum class TapeKeys { STOP, PLAY, RECORD }

    private val emulator = Emulator(roms::newMachine, options.sound, this)
    private val frame = JFrame()
    private val screen = ScreenPanel(emulator)
    private val isMac = System.getProperty("os.name").startsWith("Mac")
    private val pauseItem = JCheckBoxMenuItem("Pause")
    private val shiftLockItem = JCheckBoxMenuItem("SHIFT LOCK  (Caps Lock)")
    private var naturalLayout = false
    private var shiftLock = false
    private var joystickPort = options.joystickPort
    @Volatile private var motorRunning = false
    private var tapeName: String? = null
    private var tapeKeys = TapeKeys.STOP
    private var diskName: String? = null
    private val writeProtectItem = JCheckBoxMenuItem("Write Protect")
    @Volatile private var diskMotor = false

    fun show() {
        frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                emulator.stop()
                exitProcess(0)
            }
        })
        AppIcon.install(frame)
        frame.jMenuBar = createMenuBar()
        frame.contentPane.add(screen, BorderLayout.CENTER)
        screen.scale = options.scale
        screen.addKeyListener(keyListener)
        screen.addFocusListener(object : FocusAdapter() {
            // Keys released while another window has focus never reach us.
            override fun focusLost(e: FocusEvent) = emulator.releaseAll()
        })
        updateTitle()
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        screen.requestFocusInWindow()
        options.tape?.let { insertTape(it) }
        options.disk?.let { insertDisk(it) }
        options.program?.let { loadProgram(it, delayFrames = BOOT_FRAMES) }
        options.typeText?.let { emulator.type(it, delayFrames = BOOT_FRAMES) }
        emulator.start()
    }

    override fun frameReady(frameCount: Long) = screen.repaint()

    override fun stopped(error: Throwable) {
        SwingUtilities.invokeLater {
            pauseItem.isSelected = true
            updateTitle()
            JOptionPane.showMessageDialog(
                frame,
                "The emulated machine stopped:\n${error.message ?: error}\n\n" +
                    "Use Machine > Reset or Machine > Power Cycle to restart.",
                "C64slop",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    override fun motorChanged(on: Boolean) {
        motorRunning = on
        SwingUtilities.invokeLater { updateTitle() }
    }

    override fun diskMotorChanged(on: Boolean) {
        diskMotor = on
        SwingUtilities.invokeLater { updateTitle() }
    }

    override fun diskSaveFailed(file: File, error: IOException) {
        SwingUtilities.invokeLater {
            JOptionPane.showMessageDialog(
                frame,
                "Couldn't save the disk to ${file.name}:\n${error.message ?: error}\n\n" +
                    "Changes are no longer saved automatically. Use Disk > Save Disk As to keep them.",
                "C64slop",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    private fun log(e: KeyEvent, result: Any?) {
        if (!options.logKeys) return
        val char = if (e.keyChar == KeyEvent.CHAR_UNDEFINED) "undefined" else "'${e.keyChar}' U+%04X".format(e.keyChar.code)
        val kind = when (e.id) {
            KeyEvent.KEY_PRESSED -> "pressed "
            KeyEvent.KEY_RELEASED -> "released"
            else -> "typed   "
        }
        println(
            "$kind ${KeyEvent.getKeyText(e.keyCode)} (code ${e.keyCode}, location ${e.keyLocation}) char $char " +
                "modifiers [${InputEvent.getModifiersExText(e.modifiersEx)}] -> ${result ?: "nothing"}" +
                if (naturalLayout) "  [natural]" else "",
        )
    }

    /** What a key press does, for --log-keys. */
    private fun describe(e: KeyEvent): Any? = when {
        KeyMapping.isRestore(e.keyCode) -> "RESTORE"
        KeyMapping.isShiftLock(e.keyCode) -> "SHIFT LOCK"
        joystickPort != 0 && KeyMapping.joystickDirection(e.keyCode) != null ->
            "joystick $joystickPort ${KeyMapping.joystickDirection(e.keyCode)}"
        naturalLayout -> KeyMapping.naturalModifier(e.keyCode, e.keyLocation)
            ?: KeyMapping.naturalStroke(e.keyCode, e.keyChar, e.isControlDown, e.isAltDown || e.isAltGraphDown)
        else -> KeyMapping.forKeyCode(e.keyCode, e.keyLocation).takeIf { it.isNotEmpty() }
    }

    private val keyListener = object : KeyAdapter() {
        override fun keyTyped(e: KeyEvent) = log(e, null)

        override fun keyPressed(e: KeyEvent) {
            if (options.logKeys) log(e, describe(e))
            // Leave Command shortcuts to the menu bar.
            if (e.isMetaDown) return
            e.consume()
            if (KeyMapping.isRestore(e.keyCode)) {
                emulator.setRestore(true)
                return
            }
            if (KeyMapping.isShiftLock(e.keyCode)) {
                hostCapsLock(down = true)
                return
            }
            if (joystickPort != 0) {
                KeyMapping.joystickDirection(e.keyCode)?.let {
                    emulator.joystick(joystickPort, it, down = true)
                    return
                }
            }
            if (naturalLayout) {
                val modifier = KeyMapping.naturalModifier(e.keyCode, e.keyLocation)
                if (modifier != null) {
                    emulator.naturalModifier(modifier, down = true)
                } else {
                    val stroke = KeyMapping.naturalStroke(e.keyCode, e.keyChar, e.isControlDown, e.isAltDown || e.isAltGraphDown)
                    if (stroke != null) emulator.naturalKeyDown(e.keyCode, stroke)
                }
                return
            }
            KeyMapping.forKeyCode(e.keyCode, e.keyLocation).takeIf { it.isNotEmpty() }?.let(emulator::press)
        }

        override fun keyReleased(e: KeyEvent) {
            log(e, null)
            e.consume()
            if (KeyMapping.isRestore(e.keyCode)) {
                emulator.setRestore(false)
                return
            }
            if (KeyMapping.isShiftLock(e.keyCode)) {
                hostCapsLock(down = false)
                return
            }
            if (joystickPort != 0) {
                KeyMapping.joystickDirection(e.keyCode)?.let {
                    emulator.joystick(joystickPort, it, down = false)
                    return
                }
            }
            if (naturalLayout) {
                val modifier = KeyMapping.naturalModifier(e.keyCode, e.keyLocation)
                if (modifier != null) emulator.naturalModifier(modifier, down = false) else emulator.naturalKeyUp(e.keyCode)
                return
            }
            KeyMapping.forKeyCode(e.keyCode, e.keyLocation).takeIf { it.isNotEmpty() }?.let(emulator::release)
        }
    }

    /**
     * The host's Caps Lock is a toggle, like SHIFT LOCK. On macOS, Java reports
     * a press when it turns on and a release when it turns off; elsewhere it
     * reports both for each press, so a press flips it.
     */
    private fun hostCapsLock(down: Boolean) {
        when {
            isMac -> setShiftLock(down)
            down -> setShiftLock(!shiftLock)
        }
    }

    private fun setShiftLock(on: Boolean) {
        shiftLock = on
        shiftLockItem.isSelected = on
        emulator.setShiftLock(on)
        updateTitle()
    }

    private fun setNaturalLayout(natural: Boolean) {
        naturalLayout = natural
        // Keys held now would never be released by the other layout's handling.
        emulator.releaseAll()
        updateTitle()
        screen.requestFocusInWindow()
    }

    private fun setJoystickPort(port: Int) {
        joystickPort = port
        emulator.releaseAll()
        updateTitle()
        screen.requestFocusInWindow()
    }

    private fun createMenuBar() = JMenuBar().apply {
        add(JMenu("Machine").apply {
            pauseItem.accelerator = shortcut(KeyEvent.VK_P)
            pauseItem.addActionListener {
                emulator.paused = pauseItem.isSelected
                updateTitle()
            }
            add(pauseItem)
            add(item("Reset", shortcut(KeyEvent.VK_R)) {
                resume()
                emulator.reset()
            })
            add(item("Power Cycle", null) {
                resume()
                emulator.powerCycle()
            })
            addSeparator()
            add(item("Load Program...", shortcut(KeyEvent.VK_L)) { chooseProgram() })
            addSeparator()
            add(JCheckBoxMenuItem("Sound", options.sound).apply {
                isEnabled = options.sound
                addActionListener { emulator.volume = if (isSelected) 1f else 0f }
            })
            if (!isMac) {
                addSeparator()
                add(item("Quit", null) { frame.dispose() })
            }
        })
        add(JMenu("Edit").apply {
            add(item("Paste as Typing", shortcut(KeyEvent.VK_V)) { pasteClipboard() })
            add(item("Stop Typing", null) { emulator.cancelTyping() })
        })
        add(createKeysMenu())
        add(createTapeMenu())
        add(createDiskMenu())
        add(JMenu("Joystick").apply {
            val group = ButtonGroup()
            for ((port, title) in listOf(0 to "None", 1 to "Port 1", 2 to "Port 2")) {
                val choice = JRadioButtonMenuItem(title, port == joystickPort)
                choice.toolTipText = if (port == 0) "Arrow keys and Space go to the keyboard" else "Arrow keys move the joystick, Space fires"
                choice.addActionListener { setJoystickPort(port) }
                group.add(choice)
                add(choice)
            }
        })
        add(JMenu("View").apply {
            val group = ButtonGroup()
            for (scale in 1..3) {
                val choice = JRadioButtonMenuItem("${scale}x", scale == options.scale)
                choice.accelerator = shortcut(KeyEvent.VK_0 + scale)
                choice.addActionListener {
                    screen.scale = scale
                    frame.pack()
                }
                group.add(choice)
                add(choice)
            }
        })
        add(JMenu("Help").apply {
            add(item("Keyboard", null) { showKeyboardHelp() })
        })
    }

    /** C64 keys a host keyboard lacks, or that need a held modifier. */
    private fun createKeysMenu() = JMenu("Keys").apply {
        add(item("RUN/STOP + RESTORE", null) {
            resume()
            emulator.runStopRestore()
        })
        add(item("RUN/STOP  (Esc)", null) { emulator.tap(Key.RUN_STOP) })
        add(item("C= + SHIFT  (switch character set)", null) { emulator.tap(Key.COMMODORE, Key.LEFT_SHIFT) })
        add(shiftLockItem.apply { addActionListener { setShiftLock(isSelected) } })
        addSeparator()
        add(item("£  (Insert)", null) { emulator.tap(Key.POUND) })
        add(item("=  (Page Down)", null) { emulator.tap(Key.EQUALS) })
        add(item("π  (SHIFT + ↑)", null) { emulator.tap(Key.LEFT_SHIFT, Key.UP_ARROW) })
        add(item("CLR  (SHIFT + CLR/HOME)", null) { emulator.tap(Key.LEFT_SHIFT, Key.CLR_HOME) })
        addSeparator()
        add(JMenu("Function Keys").apply {
            val keys = listOf(Key.F1, Key.F3, Key.F5, Key.F7)
            for (n in 1..8) {
                val key = keys[(n - 1) / 2]
                add(item("f$n  (F$n)", null) {
                    if (n % 2 == 1) emulator.tap(key) else emulator.tap(Key.LEFT_SHIFT, key)
                })
            }
        })
        addSeparator()
        add(JMenu("Keyboard Layout").apply {
            val group = ButtonGroup()
            add(JRadioButtonMenuItem("Physical (for games)", true).also { choice ->
                choice.toolTipText = "Keys press the C64 key in the same place, so symbols follow the C64"
                choice.addActionListener { setNaturalLayout(false) }
                group.add(choice)
            })
            add(JRadioButtonMenuItem("Natural (for typing)").also { choice ->
                choice.toolTipText = "Keys type the character your own keyboard layout produces"
                choice.addActionListener { setNaturalLayout(true) }
                group.add(choice)
            })
        })
    }

    private fun createTapeMenu() = JMenu("Tape").apply {
        add(item("Insert Tape...", shortcut(KeyEvent.VK_O)) { chooseTape() })
        add(item("New Blank Tape", null) {
            emulator.insertTape(Tape.blank())
            tapeName = "blank tape"
            updateTitle()
        })
        add(item("Save Tape As...", shortcut(KeyEvent.VK_S)) { saveTape() })
        addSeparator()
        add(item("Play", null) { setTapeKeys(TapeKeys.PLAY) })
        add(item("Record", null) { setTapeKeys(TapeKeys.RECORD) })
        add(item("Stop", null) { setTapeKeys(TapeKeys.STOP) })
        add(item("Rewind", null) {
            emulator.rewindTape()
            tapeKeys = TapeKeys.STOP
            updateTitle()
        })
        add(item("Eject", null) {
            emulator.ejectTape()
            tapeName = null
            tapeKeys = TapeKeys.STOP
            updateTitle()
        })
        addSeparator()
        add(JCheckBoxMenuItem("Fast Loading").apply {
            toolTipText = "Run flat out while the cassette motor runs"
            addActionListener {
                emulator.fastTape = isSelected
                updateTitle()
            }
        })
    }

    /** The 1541, device 8. */
    private fun createDiskMenu() = JMenu("Disk").apply {
        if (!emulator.hasDrive) {
            isEnabled = false
            toolTipText = "No disk drive: put dos1541.rom in the ROM directory"
        }
        add(item("Insert Disk...", shortcut(KeyEvent.VK_D)) { chooseDisk() })
        add(item("New Blank Disk", null) {
            emulator.insertDisk(Disk.blank(), null)
            diskName = "blank disk"
            writeProtectItem.isSelected = false
            updateTitle()
        })
        add(item("Save Disk As...", null) { saveDisk() })
        addSeparator()
        add(writeProtectItem.apply {
            toolTipText = "Writes fail with WRITE PROTECT ON, like a disk with its notch covered"
            addActionListener { emulator.setWriteProtected(isSelected) }
        })
        add(item("Eject", null) {
            emulator.ejectDisk()
            diskName = null
            writeProtectItem.isSelected = false
            updateTitle()
        })
    }

    private fun setTapeKeys(keys: TapeKeys) {
        tapeKeys = keys
        emulator.setTapeKeys(play = keys == TapeKeys.PLAY, record = keys == TapeKeys.RECORD)
        updateTitle()
    }

    private fun item(title: String, key: KeyStroke?, action: () -> Unit) =
        JMenuItem(object : AbstractAction(title) {
            override fun actionPerformed(e: ActionEvent) = action()
        }).apply { accelerator = key }

    /** Command on macOS. Ctrl+Shift elsewhere, so plain Ctrl stays the C64's. */
    private fun shortcut(keyCode: Int): KeyStroke = KeyStroke.getKeyStroke(
        keyCode,
        if (isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK,
    )

    private fun resume() {
        emulator.paused = false
        pauseItem.isSelected = false
        updateTitle()
    }

    private fun updateTitle() {
        frame.title = "C64slop - Commodore 64" +
            (if (shiftLock) "  [SHIFT LOCK]" else "") +
            (if (naturalLayout) "  [NATURAL]" else "") +
            (if (joystickPort != 0) "  [JOYSTICK $joystickPort]" else "") +
            (tapeName?.let { "  [$it]" } ?: "") +
            (if (tapeKeys != TapeKeys.STOP) "  [${tapeKeys.name}]" else "") +
            (if (emulator.fastTape) "  [FAST]" else "") +
            (if (motorRunning) "  [MOTOR]" else "") +
            (diskName?.let { "  [8: $it]" } ?: "") +
            (if (diskMotor) "  [DISK]" else "") +
            (if (emulator.paused) "  (paused)" else "")
    }

    private fun chooseTape() {
        val dialog = FileDialog(frame, "Insert Tape", FileDialog.LOAD)
        dialog.directory = defaultTapeDirectory().path
        dialog.isVisible = true
        val name = dialog.file
        if (name != null) insertTape(File(dialog.directory, name))
        screen.requestFocusInWindow()
    }

    private fun insertTape(file: File) {
        try {
            emulator.insertTape(TapeFile.load(file))
            tapeName = file.name
            updateTitle()
        } catch (e: IOException) {
            JOptionPane.showMessageDialog(frame, "Couldn't load ${file.name}:\n${e.message ?: e}", "Insert Tape", JOptionPane.ERROR_MESSAGE)
        }
    }

    /** Writes the cassette, with anything recorded on it, to a TAP file. */
    private fun saveTape() {
        val dialog = FileDialog(frame, "Save Tape As", FileDialog.SAVE)
        dialog.directory = saveDirectory(defaultTapeDirectory()).path
        dialog.file = tapeName?.takeIf { it.endsWith(".tap") } ?: "tape.tap"
        dialog.isVisible = true
        val name = dialog.file
        if (name == null) {
            screen.requestFocusInWindow()
            return
        }
        val file = File(dialog.directory, if (name.endsWith(".tap")) name else "$name.tap")
        emulator.exportTape { image ->
            SwingUtilities.invokeLater {
                if (image == null) {
                    JOptionPane.showMessageDialog(frame, "There is no tape in the datasette.", "Save Tape", JOptionPane.WARNING_MESSAGE)
                    return@invokeLater
                }
                try {
                    TapeFile.save(image, file)
                    tapeName = file.name
                    updateTitle()
                } catch (e: IOException) {
                    JOptionPane.showMessageDialog(frame, "Couldn't save ${file.name}:\n${e.message ?: e}", "Save Tape", JOptionPane.ERROR_MESSAGE)
                }
            }
        }
        screen.requestFocusInWindow()
    }

    private fun chooseDisk() {
        val dialog = FileDialog(frame, "Insert Disk", FileDialog.LOAD)
        dialog.directory = defaultDiskDirectory().path
        dialog.isVisible = true
        val name = dialog.file
        if (name != null) insertDisk(File(dialog.directory, name))
        screen.requestFocusInWindow()
    }

    private fun insertDisk(file: File) {
        try {
            val disk = DiskFile.load(file)
            // A disk inside the app keeps its changes in memory until Save Disk As.
            emulator.insertDisk(disk, file.takeUnless(::isBundled))
            diskName = file.name
            writeProtectItem.isSelected = disk.writeProtected
            updateTitle()
        } catch (e: IOException) {
            JOptionPane.showMessageDialog(frame, "Couldn't load ${file.name}:\n${e.message ?: e}", "Insert Disk", JOptionPane.ERROR_MESSAGE)
        }
    }

    /** Writes the disk to a D64 file, which then keeps receiving its changes. */
    private fun saveDisk() {
        val dialog = FileDialog(frame, "Save Disk As", FileDialog.SAVE)
        dialog.directory = saveDirectory(defaultDiskDirectory()).path
        dialog.file = diskName?.takeIf { it.endsWith(".d64") } ?: "disk.d64"
        dialog.isVisible = true
        val name = dialog.file
        if (name == null) {
            screen.requestFocusInWindow()
            return
        }
        val chosen = File(dialog.directory, if (name.endsWith(".d64")) name else "$name.d64")
        if (isBundled(chosen)) {
            JOptionPane.showMessageDialog(frame, "Disks can't be saved inside the app. Choose another folder.", "Save Disk", JOptionPane.ERROR_MESSAGE)
            screen.requestFocusInWindow()
            return
        }
        emulator.saveDiskAs(chosen) { error ->
            SwingUtilities.invokeLater {
                if (error != null) {
                    JOptionPane.showMessageDialog(frame, "Couldn't save ${chosen.name}:\n${error.message ?: error}", "Save Disk", JOptionPane.ERROR_MESSAGE)
                } else {
                    diskName = chosen.name
                    updateTitle()
                }
            }
        }
        screen.requestFocusInWindow()
    }

    private fun chooseProgram() {
        val dialog = FileDialog(frame, "Load Program", FileDialog.LOAD)
        dialog.directory = defaultProgramDirectory().path
        dialog.isVisible = true
        val name = dialog.file
        if (name != null) {
            resume()
            loadProgram(File(dialog.directory, name))
        }
        screen.requestFocusInWindow()
    }

    /** Puts a PRG file in memory; BASIC programs are RUN, machine code needs its SYS address. */
    private fun loadProgram(file: File, delayFrames: Int = 0) {
        val program = try {
            ProgramFile.load(file)
        } catch (e: IOException) {
            JOptionPane.showMessageDialog(frame, "Couldn't load ${file.name}:\n${e.message ?: e}", "Load Program", JOptionPane.ERROR_MESSAGE)
            return
        }
        emulator.loadProgram(program, run = true, delayFrames = delayFrames)
        if (!program.isBasic) {
            JOptionPane.showMessageDialog(
                frame,
                "${file.name} is machine code at $%04X-$%04X.\nStart it with SYS ${program.loadAddress} if that is its entry point."
                    .format(program.loadAddress, program.endAddress - 1),
                "Load Program",
                JOptionPane.INFORMATION_MESSAGE,
            )
        }
    }

    private fun pasteClipboard() {
        val text = try {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
        } catch (e: Exception) {
            return
        }
        resume()
        val skipped = emulator.type(text)
        if (skipped.isNotEmpty()) {
            JOptionPane.showMessageDialog(
                frame,
                "The C64 keyboard can't type these characters, so they were skipped:\n" +
                    skipped.joinToString(" ") { if (it.isWhitespace()) "U+%04X".format(it.code) else it.toString() },
                "Paste as Typing",
                JOptionPane.WARNING_MESSAGE,
            )
        }
    }

    private fun showKeyboardHelp() {
        JOptionPane.showMessageDialog(
            frame,
            """
            Keys > Keyboard Layout chooses how keys map. [NATURAL] in the title bar shows it.
            Natural types the character your own keyboard produces, and the key you
            pressed last replaces any key still held down.
            Physical, the default, is for games and keeps the C64's symbols:
              Shift+2 = "   Shift+7 = '   Shift+8 = (   Shift+; = [
              - is +   = is -   [ is @   ] is *   \ is ↑   ; is :   ' is ;   ` is ←

            C64 key          Host key
            RUN/STOP         Esc   (Keys menu: RUN/STOP + RESTORE)
            RESTORE          Page Up or F12
            CLR/HOME         Home   (Shift+Home clears the screen)
            INST/DEL         Backspace or Delete   (Shift: insert)
            CTRL             Ctrl or Tab
            C=               Option/Alt   (C= + SHIFT switches to lower case)
            SHIFT LOCK       Caps Lock
            £ and =          Insert and Page Down
            f1-f8            F1-F8   (on a Mac, hold fn if they work the screen or sound)
            Cursor keys      Arrows; Left and Up press SHIFT for you

            With a joystick on (Joystick menu), the arrows move it and Space fires.

            Paste as Typing types text from the clipboard, letters in either case as capitals.

            Programs: Machine > Load Program puts a PRG file in memory, and RUNs it if it is BASIC.

            Tapes: insert one from the Tape menu, type LOAD and RETURN, then Tape > Play.
            To save, use Tape > New Blank Tape, SAVE "NAME", Tape > Record, then Tape > Save Tape As.
            Tape > Fast Loading skips the wait; [MOTOR] shows the motor is running.

            Disks: insert a D64 from the Disk menu, then LOAD "$",8 and LIST for the directory,
            and LOAD "NAME",8 (or LOAD "*",8 for the first file) and RUN.
            Changes are saved back to the image file; [DISK] shows the drive is running.
            """.trimIndent(),
            "Keyboard",
            JOptionPane.PLAIN_MESSAGE,
        )
    }
}
