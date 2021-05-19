package com.wolfskeep.icfp2006_2020

import java.io.*
import java.time.*
import java.util.*
import net.bytebuddy.*
import net.bytebuddy.dynamic.scaffold.*
import net.bytebuddy.description.method.*
import net.bytebuddy.description.type.*
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy
import net.bytebuddy.implementation.*
import net.bytebuddy.implementation.bytecode.*
import net.bytebuddy.implementation.bytecode.assign.TypeCasting
import net.bytebuddy.implementation.bytecode.collection.ArrayAccess
import net.bytebuddy.implementation.bytecode.constant.*
import net.bytebuddy.implementation.bytecode.member.*
import net.bytebuddy.jar.asm.*
import net.bytebuddy.jar.asm.Opcodes.*
import net.bytebuddy.matcher.ElementMatchers.named
import org.jline.keymap.*
import org.jline.reader.*
import org.jline.terminal.*

fun main(args: Array<String>) {
    val traceArgs = args.filter { it.startsWith("-t") }.map { it.toLowerCase() }
    Ref.Companion.traceFrag = traceArgs.any { "-tfragments".startsWith(it) }
    Ref.Companion.traceSetMem = traceArgs.any { "-tsetmem".startsWith(it) }
    Ref.Companion.traceSetReg = traceArgs.any { "-toperations".startsWith(it) }
    Ref.Companion.traceOp = traceArgs.any { "-toperations".startsWith(it) }
    Ref.Companion.classSave = args.any { "-save".startsWith(it) && it.startsWith("-s") }
    val file = File(args.find { it[0] != '-' })
    if (!file.canRead()) {
        System.err.println("Cannot read file ${args[0]}")
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
    val arrays = Array<IntArray>(first and 0x0fffffff) {
        val arr = IntArray(ds.readInt())
        for (k in 0 until arr.size) arr[k] = ds.readInt()
        arr
    }
    val nextAvailable = ds.readInt()

    print(if (outBuffer[0].toInt() == 0) {
        outBuffer.dropWhile { it.toInt() == 0 }
    } else {
        outBuffer.dropWhile { it.toInt() != 10 }
    }.joinToString("") { it.toChar().toString() })
    System.out.flush()

    return UM(arrays, nextAvailable, registers, finger, outBuffer)
}

interface Runner {
    fun run(um: UM, registers: IntArray, a0: IntArray, op: Int, finger: Int, arrays: Array<IntArray>)
}

open class StackOps {
    var traceFrag = false
    var traceSetReg = false
    var traceSetMem = false
    var traceOp = false
    var classSave = false

    fun getRegister(which: Int): StackManipulation {
        return getRegister(IntegerConstant.forValue(which))
    }

    fun getRegister(which: StackManipulation): StackManipulation {
        return StackManipulation.Compound(
                getRegisters,
                which,
                ArrayAccess.INTEGER.load()
        )
    }

    fun setRegister(which: Int, value: StackManipulation): StackManipulation {
        return setRegister(IntegerConstant.forValue(which), value)
    }

    fun setRegister(which: StackManipulation, value: StackManipulation): StackManipulation {
        return StackManipulation.Compound(
            getRegisters,
            which,
            value,
            ArrayAccess.INTEGER.store()
        )
    }

    fun invokeUMMethod(name: String, vararg args: StackManipulation): StackManipulation {
        val method = MethodDescription.ForLoadedMethod(UM::class.java.getMethod(name, *Array<Class<*>>(args.size) { Int::class.java }))
        return StackManipulation.Compound(
            MethodVariableAccess.REFERENCE.loadFrom(1),
            *args,
            MethodInvocation.invoke(method)
        )
    }

    val getRegisters = MethodVariableAccess.REFERENCE.loadFrom(2)
    val getArrays = invokeUMMethod("getArrays")
    val getFinger = invokeUMMethod("getFinger")
    fun setFinger(where: StackManipulation) = invokeUMMethod("setFinger", where)
    val cleanExit = invokeUMMethod("cleanExit")
    fun allocate(size: StackManipulation) = invokeUMMethod("allocate", size)
    fun free(which: StackManipulation) = invokeUMMethod("free", which)
    val input = invokeUMMethod("input")
    fun output(what: StackManipulation) = invokeUMMethod("output", what)
    fun doCloneArray(which: StackManipulation) = invokeUMMethod("doCloneArray", which)
    fun traceFrag(pos: Int) = if (traceFrag) invokeUMMethod("traceFrag", IntegerConstant.forValue(pos)) else StackManipulation.Trivial.INSTANCE
    fun traceOp(pos: Int, op: Int, aPos: Int, bPos: Int, cPos: Int) = if (traceOp) invokeUMMethod("traceOp", IntegerConstant.forValue(pos), IntegerConstant.forValue(op), IntegerConstant.forValue(aPos), IntegerConstant.forValue(bPos), IntegerConstant.forValue(cPos)) else StackManipulation.Trivial.INSTANCE
    fun traceSetReg(which: Int, pos: Int) = if (traceSetReg) invokeUMMethod("traceSetReg", IntegerConstant.forValue(which), IntegerConstant.forValue(pos)) else StackManipulation.Trivial.INSTANCE
    fun traceSetMem(array: StackManipulation, offset: StackManipulation, value: StackManipulation) = if (traceSetMem) invokeUMMethod("traceSetMem", array, offset, value) else StackManipulation.Trivial.INSTANCE

    val clear = MethodInvocation.invoke(MethodDescription.ForLoadedMethod(SortedMap::class.java.getMethod("clear")))
    val clone = MethodInvocation.invoke(MethodDescription.ForLoadedMethod(Object::class.java.getDeclaredMethod("clone")))
/*
    val arrayList_get = StackManipulation.Compound(
        MethodInvocation.invoke(MethodDescription.ForLoadedMethod(java.util.List::class.java.getDeclaredMethod("get", Int::class.java))),
        TypeCasting.to(TypeDescription.ForLoadedType(IntArray::class.java))
    )
*/
    val arrayList_get = StackManipulation.Compound(
        ArrayAccess.REFERENCE.load(),
        TypeCasting.to(TypeDescription.ForLoadedType(IntArray::class.java))
    )
}

class VisitLabel(val label: Label): StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitLabel(label)
        mv.visitFrame(F_SAME, 0, null, 0, null)
        return StackManipulation.Size(0, 0)
    }
}

class TableSwitch(val min: Int, val max: Int, val default: Label, val table: Array<Label>): StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitTableSwitchInsn(min, max, default, *table)
        return StackManipulation.Size(-1, 0)
    }
}

class JumpIfZero(val label: Label): StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitJumpInsn(IFEQ, label)
        return StackManipulation.Size(-1, 0)
    }
}

class JumpIfNotZero(val label: Label): StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitJumpInsn(IFNE, label)
        return StackManipulation.Size(-1, 0)
    }
}

class JumpIfEqual(val label: Label): StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitJumpInsn(IF_ICMPEQ, label)
        return StackManipulation.Size(-2, 0)
    }
}

class JumpIfLessThan(val label: Label): StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitJumpInsn(IF_ICMPLT, label)
        return StackManipulation.Size(-2, 0)
    }
}

class JumpIfGreaterThanOrEqual(val label: Label): StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitJumpInsn(IF_ICMPGE, label)
        return StackManipulation.Size(-2, 0)
    }
}

class JumpAlways(val label: Label): StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitJumpInsn(GOTO, label)
        return StackManipulation.Size(0, 0)
    }
}

object UInt2Long: StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitInsn(I2L)
        LongConstant.forValue(0xffffffff).apply(mv, context)
        mv.visitInsn(LAND)
        return StackManipulation.Size(1, 4)
    }
}

object DivideLong2UInt: StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitInsn(LDIV)
        mv.visitInsn(L2I)
        return StackManipulation.Size(-3, 0)
    }
}

object IAnd: StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitInsn(IAND)
        return StackManipulation.Size(-1, 0)
    }
}

object IUShr: StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitInsn(IUSHR)
        return StackManipulation.Size(-1, 0)
    }
}

object NotAnd: StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitInsn(IAND)
        mv.visitInsn(ICONST_M1)
        mv.visitInsn(IXOR)
        return StackManipulation.Size(-1, 0)
    }
}

object Not: StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitInsn(ICONST_M1)
        mv.visitInsn(IXOR)
        return StackManipulation.Size(0, 1)
    }
}

object Swap: StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitInsn(SWAP)
        return StackManipulation.Size(0, 0)
    }
}

object Dup2_x1: StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitInsn(DUP2_X1)
        return StackManipulation.Size(2, 2)
    }
}

object Dup_x1: StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitInsn(DUP_X1)
        return StackManipulation.Size(1, 1)
    }
}

object Dup_x2: StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitInsn(DUP_X2)
        return StackManipulation.Size(1, 1)
    }
}

class IInc(val which: Int, val increment: Int): StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitIincInsn(which, increment)
        return StackManipulation.Size(0, 0)
    }
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

class CleanExitException(): RuntimeException()

object MakeRunner: StackOps() {

    val mainLoop = Label()

    val getArrayZero = StackManipulation.Compound(
        MethodVariableAccess.REFERENCE.loadFrom(6), // getArrays,
        IntegerConstant.forValue(0),
        arrayList_get,
        MethodVariableAccess.REFERENCE.storeAt(3)
    )

    val a = StackManipulation.Compound(
        MethodVariableAccess.INTEGER.loadFrom(4),
        IntegerConstant.forValue(6),
        IUShr,
        IntegerConstant.forValue(7),
        IAnd
    )
    val b = StackManipulation.Compound(
        MethodVariableAccess.INTEGER.loadFrom(4),
        IntegerConstant.forValue(3),
        IUShr,
        IntegerConstant.forValue(7),
        IAnd
    )
    val c = StackManipulation.Compound(
        MethodVariableAccess.INTEGER.loadFrom(4),
        IntegerConstant.forValue(7),
        IAnd
    )
    val d = StackManipulation.Compound(
        MethodVariableAccess.INTEGER.loadFrom(4),
        IntegerConstant.forValue(25),
        IUShr,
        IntegerConstant.forValue(7),
        IAnd
    )
    val v = StackManipulation.Compound(
        MethodVariableAccess.INTEGER.loadFrom(4),
        IntegerConstant.forValue(0x1ffffff),
        IAnd
    )

    fun wrap(label: Label, code: StackManipulation): StackManipulation {
        return StackManipulation.Compound(
            VisitLabel(label),
            code,
            JumpAlways(mainLoop)
        )
    }

    val opIf = StackManipulation.Compound(
        getRegister(c),
        JumpIfZero(mainLoop),
        setRegister(a, getRegister(b))
    )

    val opFetch = 
        setRegister(a, StackManipulation.Compound(
            MethodVariableAccess.REFERENCE.loadFrom(6), // getArrays,
            getRegister(b),
            arrayList_get,
            getRegister(c),
            ArrayAccess.INTEGER.load()
        ))

    val opStore = StackManipulation.Compound(
        MethodVariableAccess.REFERENCE.loadFrom(6), // getArrays,
        getRegister(a),
        arrayList_get,
        getRegister(b),
        getRegister(c),
        ArrayAccess.INTEGER.store()
    )

    val opAdd =
        setRegister(a, StackManipulation.Compound(
            getRegister(b),
            getRegister(c),
            Addition.INTEGER
        ))

    val opMul =
        setRegister(a, StackManipulation.Compound(
            getRegister(b),
            getRegister(c),
            Multiplication.INTEGER
        ))

    val opDiv =
        setRegister(a, StackManipulation.Compound(
            getRegister(b),
            UInt2Long,
            getRegister(c),
            UInt2Long,
            DivideLong2UInt
        ))

    val opNand =
        setRegister(a, StackManipulation.Compound(
            getRegister(b),
            getRegister(c),
            NotAnd
        ))

    val opHalt = cleanExit

    val opNew = StackManipulation.Compound(
        setRegister(b, allocate(getRegister(c))),
        getArrays,
        MethodVariableAccess.REFERENCE.storeAt(6)
    )

    val opFree = free(getRegister(c))
    val opOutput = output(getRegister(c))
    val opInput = StackManipulation.Compound(
        setFinger(MethodVariableAccess.INTEGER.loadFrom(5)),
        setRegister(c, input)
    )

    val jumpLabel = Label()
    val opJump = StackManipulation.Compound(
        getRegister(b),
        JumpIfZero(jumpLabel),
        doCloneArray(getRegister(b)),
        getArrayZero,
        VisitLabel(jumpLabel),
        getRegister(c),
        MethodVariableAccess.INTEGER.storeAt(5)
    )

    val opConst = setRegister(d, v)

    val opLabels = Array<Label>(14) { Label() }
    val opCodes = arrayOf(
        wrap(opLabels[0], opIf),
        wrap(opLabels[1], opFetch),
        wrap(opLabels[2], opStore),
        wrap(opLabels[3], opAdd),
        wrap(opLabels[4], opMul),
        wrap(opLabels[5], opDiv),
        wrap(opLabels[6], opNand),
        wrap(opLabels[7], opHalt),
        wrap(opLabels[8], opNew),
        wrap(opLabels[9], opFree),
        wrap(opLabels[10], opOutput),
        wrap(opLabels[11], opInput),
        wrap(opLabels[12], opJump),
        wrap(opLabels[13], opConst)
    )

    val default = Label()
    val full = StackManipulation.Compound(
        getArrayZero,
        VisitLabel(mainLoop),
        MethodVariableAccess.REFERENCE.loadFrom(3),
        MethodVariableAccess.INTEGER.loadFrom(5),
        IInc(5, 1),
        ArrayAccess.INTEGER.load(),
        Duplication.SINGLE,
        MethodVariableAccess.INTEGER.storeAt(4),
        IntegerConstant.forValue(28),
        IUShr,
        TableSwitch(0, 13, default, opLabels),
        *opCodes,
        VisitLabel(default),
        MethodReturn.VOID
    )

    val bca = object: ByteCodeAppender {
        override fun apply(mv: MethodVisitor, context: Implementation.Context, desc: MethodDescription): ByteCodeAppender.Size {
            val size = full.apply(mv, context)
            return ByteCodeAppender.Size(size.getMaximalSize(), 7)
        }
    }

    val runner = ByteBuddy()
        .subclass(Runner::class.java)
        .method(named("run")).intercept(object: Implementation {
            override fun prepare(instrumentedType: InstrumentedType) = instrumentedType
            override fun appender(target: Implementation.Target?): ByteCodeAppender? {
                return bca
            }
        })
        .make()
        .load(Runner::class.java.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
        .getLoaded().newInstance() as Runner
}

class UM(
        var arrays: Array<IntArray>,
        var nextAvailable: Int,
        val registers: IntArray,
        var finger: Int,
        val outBuffer: ByteArray
) {
    constructor(a0: IntArray): this(
            arrayOf(a0),
            -1,
            IntArray(8),
            0,
            ByteArray(256)
    ) {}

    val blank = IntArray(0)
    var operator = 0
    var outPos = 0

    var terminalOut: PrintWriter? = null
    var terminalIn: LineReader? = null
    var pendingLine = ""

    var maxAlloc = 0

    fun run() {
        TerminalBuilder.terminal().use { terminal ->
            val ti = LineReaderBuilder.builder().terminal(terminal).build()
            terminalIn = ti
            val map = ti.getKeyMaps().get(LineReader.MAIN)!!
            val binding = object: Widget {
                override fun apply(): Boolean { dumpState(); return true }
            }
            map.bind(binding, KeyMap.ctrl('G'))

            terminalOut = terminal.writer()

            try {
                MakeRunner.runner.run(this, registers, arrays[0], 0, finger, arrays)
            } catch (e: CleanExitException) {} 
        }
        System.err.println("maxAlloc: $maxAlloc")
    }

    fun traceFrag(pos: Int) {
        System.err.println("TRACE: Starting fragment at $pos with [${registers.joinToString(", ") { it.toString() }}]")
    }

    fun traceOp(pos: Int, op: Int, aPos: Int, bPos: Int, cPos: Int) {
        System.err.println("$pos: ${decode(op)}  {A:$aPos, B:$bPos, C:$cPos}")
    }

    fun traceSetReg(which: Int, pos: Int) {
        System.err.println("SetReg: ${"abcdefgh"[which].toString()} {$pos}")
    }

    fun traceSetMem(array: Int, offset: Int, value: Int) {
        System.err.println("TRACE: Setting $array[$offset] = $value")
    }

    fun doCloneArray(which: Int) {
        arrays[0] = arrays[which].clone()
    }

    fun cleanExit() {
        throw CleanExitException()
    }

    fun allocate(size: Int): Int {
        if (nextAvailable < 0) {
            val oldSize = arrays.size
            val newSize = if (oldSize < 16) 16 else oldSize * 2
            arrays = Arrays.copyOf(arrays, newSize)
            var n = oldSize + 1
            while (n < newSize) {
                arrays[n] = IntArray(8)
                arrays[n][0] = n + 1
                n = n + 1
            }
            arrays[newSize - 1][0] = -1
            arrays[oldSize] = IntArray(size + 1)
            nextAvailable = oldSize + 1
            maxAlloc = oldSize
            return oldSize
        } else {
            val which = nextAvailable
            val arr = arrays[which]
            nextAvailable = arr[0]
            if (maxAlloc < which) maxAlloc = which
            if (arr.size < size) {
                arrays[which] = IntArray(size + 1)
            } else {
                Arrays.fill(arr, 0)
            }
            return which
        }
    }

    fun free(which: Int) {
        arrays[which][0] = nextAvailable
        nextAvailable = which
    }

    fun input(): Int {
        return try {
            if (pendingLine == "") {
                pendingLine = terminalIn!!.readLine() + "\n"
            }
            outBuffer[outPos] = pendingLine[0].toByte()
            val ch = outBuffer[outPos].toInt()
            outPos = (outPos + 1) % outBuffer.size
            pendingLine = pendingLine.drop(1)
            ch
        } catch (e: EOFException) { -1 }
    }

    fun output(what: Int) {
        terminalOut!!.write(what)
        outBuffer[outPos] = what.toByte()
        outPos = (outPos + 1) % outBuffer.size
        terminalOut!!.flush()
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
            out.writeInt(nextAvailable)
        }
        println("Saved")
    }
}
