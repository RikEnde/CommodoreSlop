// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

package c64slop.desktop

import c64slop.c64.C64Clock
import c64slop.c64.C64Machine
import c64slop.c64.Joystick
import c64slop.c64.KeyboardMatrix.Key
import c64slop.c64.KeyboardText
import c64slop.c64.Program
import c64slop.c64.Tape
import c64slop.c64.Typist
import c64slop.c64.Vic6569
import c64slop.c64.load
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Runs one C64 on a dedicated thread in real time, one VIC-II frame (a 50th of
 * a second) at a time. Adapted from Beebslop's `Emulator`.
 *
 * Only the emulation thread touches the machine. Other threads talk to it
 * through [post] and read the picture through [copyFrame]. With sound on, the
 * blocking audio device sets the pace (its sample clock is the master clock);
 * without sound, the thread sleeps to match emulated time to wall time. With
 * [fastTape] on, it runs flat out while the cassette motor runs, so software
 * still sees the real timing and can't tell.
 */
class Emulator(
    private val createMachine: () -> C64Machine,
    private val soundEnabled: Boolean,
    private val listener: Listener,
) {
    interface Listener {
        /** Called on the emulation thread after each completed frame. */
        fun frameReady(frameCount: Long)

        /** Called on the emulation thread when the machine stops on an error. */
        fun stopped(error: Throwable)

        /** Called on the emulation thread when the cassette motor starts or stops. */
        fun motorChanged(on: Boolean) {}
    }

    private val commands = ConcurrentLinkedQueue<(C64Machine) -> Unit>()
    private val frameLock = Any()
    private val published = IntArray(Vic6569.WIDTH * Vic6569.HEIGHT)
    private val typist = Typist()
    private val natural = NaturalKeyboard()
    private var machine = createMachine()
    private var thread: Thread? = null
    private var motorRunning = false

    /** How many held host keys press each C64 key, as two can share one (Shift and Up). */
    private val held = mutableMapOf<Key, Int>()

    /** Frames left until RESTORE is let go, after a RUN/STOP + RESTORE from the menu. */
    private var restoreFrames = 0

    @Volatile private var running = false
    @Volatile var paused = false
    @Volatile var volume = 1f
    @Volatile var fastTape = false
    @Volatile var frameCount = 0L
        private set

    fun start() {
        check(thread == null)
        running = true
        thread = Thread(::run, "c64-emulation").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    /** Run [command] on the emulation thread before the next frame. */
    fun post(command: (C64Machine) -> Unit) { commands += command }

    fun press(keys: List<Key>) = post { machine ->
        for (key in keys) {
            held[key] = (held[key] ?: 0) + 1
            machine.keyboard.press(key)
        }
    }

    fun release(keys: List<Key>) = post { machine ->
        for (key in keys) {
            val count = (held[key] ?: continue) - 1
            if (count > 0) {
                held[key] = count
            } else {
                held -= key
                machine.keyboard.release(key)
            }
        }
    }

    /** RESTORE, which pulls NMI low while it is down. */
    fun setRestore(down: Boolean) = post { it.keyboard.restore = down }

    fun setShiftLock(on: Boolean) = post { it.keyboard.shiftLock = on }

    /** Let go of every key and both joysticks, for when key-up events may have been lost. */
    fun releaseAll() = post {
        it.keyboard.releaseAll()
        it.joystick1.releaseAll()
        it.joystick2.releaseAll()
        held.clear()
        natural.reset()
    }

    fun naturalModifier(key: Key, down: Boolean) = post { natural.modifier(key, down) }
    fun naturalKeyDown(hostKey: Int, stroke: KeyMapping.NaturalStroke) = post { natural.keyDown(hostKey, stroke) }
    fun naturalKeyUp(hostKey: Int) = post { natural.keyUp(hostKey) }

    /** Move the joystick in port 1 or 2. */
    fun joystick(port: Int, direction: Joystick.Direction, down: Boolean) = post {
        val stick = if (port == 1) it.joystick1 else it.joystick2
        if (down) stick.press(direction) else stick.release(direction)
    }

    /** A reset button on the user port: RAM survives, so a program in memory can often be restarted. */
    fun reset() = post {
        typist.cancel(it)
        it.reset()
    }

    /**
     * RUN/STOP + RESTORE: the KERNAL's NMI handler sees RUN/STOP held and
     * restores the screen and I/O, stopping a BASIC program.
     */
    fun runStopRestore() = post {
        typist.enqueueChord(listOf(Key.RUN_STOP), holdFrames = RESTORE_FRAMES) { machine ->
            machine.keyboard.restore = true
            restoreFrames = RESTORE_FRAMES
        }
    }

    /** Press keys together briefly, for keys a menu click can't hold down. */
    fun tap(vararg keys: Key) = post { typist.enqueueChord(keys.toList()) }

    /** Type [text]; characters the C64 cannot type are skipped and returned. */
    fun type(text: String, delayFrames: Int = 0): Set<Char> {
        val skipped = text.filter { it != '\t' && it != '\r' && !KeyboardText.canType(it) }.toSet()
        post { typist.enqueue(text, delayFrames) }
        return skipped
    }

    fun cancelTyping() = post { typist.cancel(it) }

    /**
     * Put [program] in memory after [delayFrames], as `LOAD "NAME",8,1` would,
     * then type `RUN` if it is BASIC and [run] is set.
     */
    fun loadProgram(program: Program, run: Boolean, delayFrames: Int = 0) = post {
        typist.enqueueAction(delayFrames) { machine -> machine.load(program) }
        if (run && program.isBasic) typist.enqueue("RUN\n")
    }

    /** Put a cassette in the datasette, as it is wound. */
    fun insertTape(cassette: Tape) = post { it.datasette.tape = cassette }

    /** Take the cassette out; the keys pop up. */
    fun ejectTape() = post {
        it.datasette.tape = null
        it.datasette.playDown = false
        it.datasette.recordDown = false
    }

    /** Rewind to the start; like the real datasette, this lets PLAY and RECORD up. */
    fun rewindTape() = post {
        it.datasette.playDown = false
        it.datasette.recordDown = false
        it.datasette.tape?.rewind()
    }

    /** Press PLAY, PLAY and RECORD, or STOP (both false). */
    fun setTapeKeys(play: Boolean, record: Boolean) = post {
        it.datasette.playDown = play || record
        it.datasette.recordDown = record
    }

    /** The cassette as a TAP image, delivered to [receive] on the emulation thread. */
    fun exportTape(receive: (ByteArray?) -> Unit) = post { receive(it.datasette.tape?.toTap()) }

    /** Switch off and on: fresh RAM and chips. The cassette stays in, with its keys as they were. */
    fun powerCycle() = post { old ->
        typist.cancel(old)
        natural.reset()
        held.clear()
        restoreFrames = 0
        machine = createMachine().also { fresh ->
            fresh.datasette.tape = old.datasette.tape
            fresh.datasette.playDown = old.datasette.playDown
            fresh.datasette.recordDown = old.datasette.recordDown
            fresh.keyboard.shiftLock = old.keyboard.shiftLock
        }
        paused = false
    }

    fun copyFrame(destination: IntArray) {
        synchronized(frameLock) { published.copyInto(destination) }
    }

    private fun run() {
        val audio = if (soundEnabled) AudioOutput.open(SAMPLE_RATE, SAMPLE_RATE / 15) else null
        try {
            runWith(audio)
        } finally {
            audio?.close()
        }
    }

    private fun runWith(audio: AudioOutput?) {
        val samples = FloatArray(SAMPLE_RATE / 5)
        var soundMachine: C64Machine? = null
        var paceStart = System.nanoTime()
        var paceCycles = 0L
        while (running) {
            while (true) commands.poll()?.invoke(machine) ?: break
            if (audio != null && machine !== soundMachine) {
                machine.sid.startSampling(SAMPLE_RATE, samples.size)
                soundMachine = machine
            }
            if (paused) {
                Thread.sleep(10)
                paceStart = System.nanoTime()
                paceCycles = 0
                continue
            }
            val elapsed = try {
                runField()
            } catch (e: Exception) {
                paused = true
                listener.stopped(e)
                continue
            }
            if (fastTape && tapeMoving()) {
                soundMachine?.sid?.drainSamples(samples)
                paceStart = System.nanoTime()
                paceCycles = 0
                continue
            }
            if (audio != null) {
                audio.write(samples, soundMachine!!.sid.drainSamples(samples), volume)
                continue
            }
            paceCycles += elapsed
            val ahead = paceStart + paceCycles * 1_000_000_000L / C64Clock.CYCLES_PER_SECOND - System.nanoTime()
            if (ahead > 0) {
                Thread.sleep(ahead / 1_000_000, (ahead % 1_000_000).toInt())
            } else if (ahead < -100_000_000) {
                // Fell far behind (host stall): don't try to catch up.
                paceStart = System.nanoTime()
                paceCycles = 0
            }
        }
    }

    /** The motor runs with a tape to play or record; past the end there's nothing to hurry to. */
    private fun tapeMoving(): Boolean {
        val datasette = machine.datasette
        val cassette = datasette.tape ?: return false
        return datasette.motorRunning && (datasette.recording || !cassette.atEnd)
    }

    /** Runs one frame and publishes the picture; returns the elapsed cycles. */
    private fun runField(): Long {
        val machine = machine
        typist.onFrame(machine)
        natural.onFrame(machine.keyboard)
        if (restoreFrames > 0 && --restoreFrames == 0) machine.keyboard.restore = false
        val elapsed = runFrame(machine)
        synchronized(frameLock) { machine.vic.pixels.copyInto(published) }
        listener.frameReady(++frameCount)
        val motor = machine.datasette.motorRunning
        if (motor != motorRunning) {
            motorRunning = motor
            listener.motorChanged(motor)
        }
        if (machine.cpu.halted) {
            // Like the real machine, a jammed CPU leaves the display running and
            // only a reset recovers it. Pause so the user knows why it froze.
            paused = true
            listener.stopped(IllegalStateException("The 6510 executed a JAM opcode and halted."))
        }
        return elapsed
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val RESTORE_FRAMES = 5

        /**
         * Runs one frame: on to the VIC-II's next wrap to raster line 0, which is
         * always [C64Clock.CYCLES_PER_FRAME] cycles on the PAL chip. Returns the
         * elapsed cycles.
         */
        fun runFrame(machine: C64Machine): Long {
            val start = machine.clock.cycles
            val frame = machine.vic.frames
            while (machine.vic.frames == frame) machine.step()
            return machine.clock.cycles - start
        }
    }
}
