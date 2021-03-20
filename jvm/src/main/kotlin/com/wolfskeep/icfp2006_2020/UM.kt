package com.wolfskeep.icfp2006_2020

import java.io.*
import java.time.*
import org.jline.terminal.*

fun main(args: Array<String>) {
    val file = File(args[0])
    if (!file.canRead()) {
        println("Cannot read file ${args[0]}")
        System.exit(1)
    }
    val um = DataInputStream(BufferedInputStream(FileInputStream(file))).use { ds ->
        val first = ds.readInt()
        if ((first ushr 28) == 14) {
            loadState(ds, first)
        } else {
            val a0 = IntArray(file.length().toInt() / 4)
            a0[0] = first
            for (j in 1..(a0.size - 1)) a0[j] = ds.readInt()
            UM(a0)
        }
    }

    um.run()
}

fun loadState(ds: DataInputStream, first: Int): UM {
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

    return UM(arrays, available, registers, finger, outBuffer)
}

class UM(
    val arrays: MutableList<IntArray>,
    val available: ArrayDeque<Int>,
    val registers: IntArray,
    var finger: Int,
    val outBuffer: ByteArray
) {
    constructor(a0: IntArray): this(
        mutableListOf(a0),
        ArrayDeque<Int>(),
        IntArray(8),
        0,
        ByteArray(256)
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
            terminal.enterRawMode()
            terminal.echo(true)
            val input = terminal.input()
            val output = terminal.output()
    
            while (true) {
                operator = arrays[0][finger]
                // println("finger: $finger  operator: ${java.lang.Integer.toHexString(operator)}")
                finger += 1
                when (operator ushr 28) {
                    0 -> if (C != 0) A = B
                    1 -> A = arrays[B][C]
                    2 -> arrays[A][B] = C
                    3 -> A = B + C
                    4 -> A = (B.toUInt() * C.toUInt()).toInt()
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
                        val c = input.read()
                        if (c == 7) {
                            dumpState()
                            finger -= 1
                        } else if (c == 13) {
                            output.write(10)
                            terminal.flush()
                            C = 10
                            outBuffer[outPos] = C.toByte()
                            outPos = (outPos + 1) % outBuffer.size
                        } else {
                            C = c
                            outBuffer[outPos] = C.toByte()
                            outPos = (outPos + 1) % outBuffer.size
                        }
                    } catch (e: EOFException) { C = -1 }
                    12 -> {
                        if (B != 0) arrays[0] = arrays[B].clone()
                        finger = C
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
