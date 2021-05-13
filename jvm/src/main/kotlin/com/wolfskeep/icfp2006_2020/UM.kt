package com.wolfskeep.icfp2006_2020

import java.io.*
import java.time.*
import org.jline.keymap.*
import org.jline.reader.*
import org.jline.terminal.*

fun main(args: Array<String>) {
    val traceArgs = args.filter { it.startsWith("-t") }.map { it.toLowerCase() }
    val traceFrag = traceArgs.any { "-tfragments".startsWith(it) }
    val traceSet  = traceArgs.any { "-tset"      .startsWith(it) }
    val traceOp   = traceArgs.any { "-toperators".startsWith(it) }

    val file = File(args.find { it[0] != '-' })
    if (!file.canRead()) {
        println("Cannot read file ${args[0]}")
        System.exit(1)
    }
    val um = DataInputStream(BufferedInputStream(FileInputStream(file))).use { ds ->
        val first = ds.readInt()
        if ((first ushr 28) == 14) {
            loadState(ds, first, traceFrag, traceSet, traceOp)
        } else {
            val a0 = IntArray(file.length().toInt() / 4)
            a0[0] = first
            for (j in 1..(a0.size - 1)) a0[j] = ds.readInt()
            UM(a0, traceFrag, traceSet, traceOp)
        }
    }

    um.run()
}

fun loadState(ds: DataInputStream, first: Int, traceFrag: Boolean, traceSet: Boolean, traceOp: Boolean): UM {
    val finger = ds.readInt()
    val registers = IntArray(8)
    for (j in 0..7) registers[j] = ds.readInt()
    val outBuffer = ByteArray(256)
    ds.readFully(outBuffer)
    val arrays = ArrayList<IntArray>()
    for (j in 0 until (first and ((1 shl 28) - 1))) {
        val arr = IntArray(ds.readInt())
        for (k in 0 until arr.size) arr[k] = ds.readInt()
        arrays += arr
    }
    val available = ArrayDeque<Int>()
    for (j in 0 until ds.readInt()) available.add(ds.readInt())

    print(if (outBuffer[0].toInt() == 0) {
        outBuffer.dropWhile { it.toInt() == 0 }
    } else {
        outBuffer.dropWhile { it.toInt() != 10 }
    }.joinToString("") { it.toChar().toString() })
    System.out.flush()

    return UM(arrays, available, registers, finger, outBuffer, traceFrag, traceSet, traceOp)
}

fun decode(operator: Int): String {
    val AN: String = "abcdefgh"[operator shr 6 and 7].toString()
    val BN: String = "abcdefgh"[operator shr 3 and 7].toString()
    val CN: String = "abcdefgh"[operator shr 0 and 7].toString()
    val DN: String = "abcdefgh"[operator shr 25 and 7].toString()
    return when (operator ushr 28) {
        0 -> "IF ($CN) $AN = $BN"
        1 -> "$AN = arrays[$BN][$CN]"
        2 -> "arrays[$AN][$BN] = $CN"
        3 -> "$AN = $BN + $CN"
        4 -> "$AN = $BN * $CN"
        5 -> "$AN = $BN / $CN"
        6 -> "$AN = $BN nand $CN"
        7 -> "EXIT"
        8 -> "$BN = NEW $CN"
        9 -> "FREE $CN"
        10 -> "OUT $CN"
        11 -> "IN $CN"
        12 -> "JUMP [$BN][$CN]"
        13 -> "$DN = ${operator and 0x1ffffff}"
        14 -> "SetRegisters"
        15 -> "LoadRegister $CN"
        else -> "Illegal"
    }
}

class UM(
    val arrays: MutableList<IntArray>,
    val available: ArrayDeque<Int>,
    val registers: IntArray,
    var finger: Int,
    val outBuffer: ByteArray,
    val traceFrag: Boolean = false,
    val traceSet:  Boolean = false,
    val traceOp:   Boolean = false
) {
    constructor(a0: IntArray, traceFrag: Boolean, traceSet: Boolean, traceOp: Boolean): this(
        mutableListOf(a0),
        ArrayDeque<Int>(),
        IntArray(8),
        0,
        ByteArray(256),
        traceFrag,
        traceSet,
        traceOp
    ) {}

    val blank = IntArray(0)
    var operator = 0
    var outPos = 0

    var A: Int
        inline get() = registers[(operator shr 6) and 7]
        inline set(value) { registers[(operator shr 6) and 7] = value }
    var B: Int
        inline get() = registers[(operator shr 3) and 7]
        inline set(value) { registers[(operator shr 3) and 7] = value }
    var C: Int
        inline get() = registers[(operator shr 0) and 7]
        inline set(value) { registers[(operator shr 0) and 7] = value }
    var D: Int
        inline get() = registers[(operator shr 25) and 7]
        inline set(value) { registers[(operator shr 25) and 7] = value }
    val V: Int
        inline get() = operator and 0x01FFFFFF

    fun run() {
        TerminalBuilder.terminal().use { terminal ->
            val reader = LineReaderBuilder.builder().terminal(terminal).build()
            val map = reader.getKeyMaps().get(LineReader.MAIN)!!
            val binding = object: Widget {
                override fun apply(): Boolean { dumpState(); return true }
            }
            map.bind(binding, KeyMap.ctrl('G'))

            val output = terminal.output()
            var pendingLine = ""

            var a0 = arrays[0]
    
            while (true) {
                operator = a0[finger]
                // println("finger: $finger  operator: ${java.lang.Integer.toHexString(operator)}")
                if (traceOp) System.err.println("$finger: ${decode(operator)}")
                finger += 1
                when (operator ushr 28) {
                    0 -> if (C != 0) A = B
                    1 -> A = arrays[B][C]
                    2 -> {
                        arrays[A][B] = C
                        if (traceSet) System.err.println("TRACE: Setting $A[$B] = $C")
                    }
                    3 -> A = B + C
                    4 -> A = B * C
                    5 -> A = (B.toUInt() / C.toUInt()).toInt()
                    6 -> A = (B and C).inv()
                    7 -> return@run
                    8 -> {
                        val fresh = if (C == 0) blank else IntArray(C)
                        if (available.size == 0) {
                            B = arrays.size
                            arrays += fresh
                        } else {
                            B = available.removeLast()
                            arrays[B] = fresh
                        }
                    }
                    9 -> { arrays[C] = blank; available.add(C) }
                    10 -> {
                        output.write(C)
                        outBuffer[outPos] = C.toByte()
                        outPos = (outPos + 1) % outBuffer.size
                        terminal.flush()
                    }
                    11 -> try {
                        if (pendingLine == "") {
                            pendingLine = reader.readLine() + "\n"
                        }
                        outBuffer[outPos] = pendingLine[0].toByte()
                        C = outBuffer[outPos].toInt()
                        outPos = (outPos + 1) % outBuffer.size
                        pendingLine = pendingLine.drop(1)
                    } catch (e: EOFException) { C = -1 }
                    12 -> {
                        if (B != 0) {
                            arrays[0] = arrays[B].clone()
                            a0 = arrays[0]
                        }
                        finger = C
                        if (traceFrag) System.err.println("TRACE: Starting fragment at $finger with [${registers.joinToString(", ")}]")
                    }
                    13 -> D = V
                    else -> { println("Illegal operation ${operator ushr 28}"); System.exit(1) }
                }
            }
        }
    }

    fun dumpState() {
        val name = String.format("umDump-%1\$tY-%1\$tm-%1\$td_%1\$tH-%1\$tM-%1\$tS.um", LocalDateTime.now())
        println()
        println("Saving $name")
        DataOutputStream(BufferedOutputStream(FileOutputStream(name))).use { out ->
            out.writeInt((14 shl 28) + arrays.size)
            out.writeInt(finger - 1)
            registers.forEach(out::writeInt)
            out.write(outBuffer, outPos, outBuffer.size - outPos)
            out.write(outBuffer, 0, outPos)
            for (j in arrays) { out.writeInt(j.size); j.forEach(out::writeInt) }
            out.writeInt(available.size)
            available.forEach(out::writeInt)
        }
        println("Saved")
    }
}
