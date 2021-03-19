package com.wolfskeep.icfp2006_2020

import java.io.*
import org.jline.terminal.*

fun main(args: Array<String>) {
    val file = File(args[0])
    if (!file.canRead()) {
        println("Cannot read file ${args[0]}")
        System.exit(1)
    }
    val a0 = IntArray(file.length().toInt() / 4)
    FileInputStream(file).use { fs ->
        val ds = DataInputStream(BufferedInputStream(fs))
        for (j in 0..(a0.size - 1)) a0[j] = ds.readInt()
    }

    UM(a0).run()
}

class UM(a0: IntArray) {
    val blank = IntArray(0)
    val registers = IntArray(8)
    val arrays = mutableListOf(a0)
    val available = ArrayDeque<Int>()
    var finger = 0
    var operator = 0

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
                    10 -> { output.write(C); terminal.flush() }
                    11 -> try { C = input.read() } catch (e: EOFException) { C = -1 }
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
}
