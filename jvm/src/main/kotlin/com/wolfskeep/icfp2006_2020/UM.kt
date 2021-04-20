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
    val file = File(args[0])
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

interface Fragment {
    val start: Int
    val end: Int
    fun run(um: UM, registers: IntArray)
}

abstract class Operation(val operation: Int, touched: Array<RegOut>): StackManipulation {
    inline val code get() = operation ushr 28
    inline val a get() = operation shr 6 and 7
    inline val b get() = operation shr 3 and 7
    inline val c get() = operation shr 0 and 7
    inline val d get() = operation shr 25 and 7

    val A = touched[a]
    val B = touched[b]
    val C = touched[c]

    override fun isValid() = true

    fun getRegister(which: Int): StackManipulation {
        return StackManipulation.Compound(
                getRegisters,
                IntegerConstant.forValue(which),
                ArrayAccess.INTEGER.load()
        )
    }

    fun setRegister(which: Int, value: StackManipulation): StackManipulation {
        return StackManipulation.Compound(
            getRegisters,
            IntegerConstant.forValue(which),
            value,
            ArrayAccess.INTEGER.store()
        )
    }

    companion object {
        fun invokeUMMethod(name: String, vararg args: StackManipulation): StackManipulation {
            val method = MethodDescription.ForLoadedMethod(UM::class.java.getMethod(name, *Array<Class<*>>(args.size) { Int::class.java }))
            return StackManipulation.Compound(
                MethodVariableAccess.REFERENCE.loadFrom(1),
                *args,
                MethodInvocation.invoke(method)
            )
        }

        val getRegisters = MethodVariableAccess.REFERENCE.loadFrom(2)
        //val getRegisters = invokeUMMethod("getRegisters")
        val getArrays = invokeUMMethod("getArrays")
        val getFragments = invokeUMMethod("getFragments")
        fun setFinger(where: StackManipulation) = invokeUMMethod("setFinger", where)
        val cleanExit = invokeUMMethod("cleanExit")
        fun allocate(size: StackManipulation) = invokeUMMethod("allocate", size)
        fun free(which: StackManipulation) = invokeUMMethod("free", which)
        val input = invokeUMMethod("input")
        fun output(what: StackManipulation) = invokeUMMethod("output", what)
        fun clearCorruptedFragments(array: StackManipulation, offset: StackManipulation, value: StackManipulation, nextPos: Int, finalPos: Int) =
            invokeUMMethod("clearCorruptedFragments", array, offset, value, IntegerConstant.forValue(nextPos), IntegerConstant.forValue(finalPos))
        fun doIfAssign(a: StackManipulation, b: StackManipulation, c: StackManipulation) = invokeUMMethod("doIfAssign", a, b, c)
        fun doCloneArray(which: StackManipulation) = invokeUMMethod("doCloneArray", which)

        val clear = MethodInvocation.invoke(MethodDescription.ForLoadedMethod(SortedMap::class.java.getMethod("clear")))
        val clone = MethodInvocation.invoke(MethodDescription.ForLoadedMethod(Object::class.java.getDeclaredMethod("clone")))
        val arrayList_get = StackManipulation.Compound(
            MethodInvocation.invoke(MethodDescription.ForLoadedMethod(java.util.List::class.java.getDeclaredMethod("get", Int::class.java))),
            TypeCasting.to(TypeDescription.ForLoadedType(IntArray::class.java))
        )
    }
}

abstract class RegOut(operation: Int, touched: Array<RegOut>): Operation(operation, touched) {
    val regOut = when (code) {
        0, 1, 3, 4, 5, 6 -> a
        8 -> b
        11 -> c
        13 -> d
        else -> throw IllegalArgumentException()
    }
    init { touched[regOut] = this }

    var exposed = false
    var refCount = 0
    var scopeStart: Label? = null
    var scopeEnd: Label? = null

    open fun canBeZero(): Boolean = true
    open fun possibleValues(): Set<Int>? = null
}

object JunkReg: RegOut(-1, Array<RegOut>(8) { JunkReg }) {
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return StackManipulation.Trivial.INSTANCE.apply(mv, context)
    }
}

class SetLabel(val label: Label): StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitLabel(label)
        mv.visitFrame(F_SAME, 2, arrayOf("Lcom/wolfskeep/icfp2006_2020/UM;", "[I"), 0, arrayOf())
        return StackManipulation.Size(0, 0)
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

object NotAnd: StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitInsn(IAND)
        mv.visitInsn(ICONST_M1)
        mv.visitInsn(IXOR)
        return StackManipulation.Size(-1, 0)
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

class GET(val which: Int, touched: Array<RegOut>): RegOut(-1, touched) {
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return StackManipulation.Compound(
            getRegister(which),
            MethodVariableAccess.INTEGER.storeAt(which + 1)
        ).apply(mv, context)
    }
}

class PUT(val which: Int, touched: Array<RegOut>): Operation(-1, touched) {
    init { if (!(touched[which] is GET)) touched[which].refCount += 1 }
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return setRegister(which, MethodVariableAccess.INTEGER.loadFrom(which + 1))
            .apply(mv, context)
    }
}

class IF(operation: Int, touched: Array<RegOut>): RegOut(operation, touched) {
    val test = C
    val zero = if (test?.canBeZero() ?: true) A else null
    val nonzero = if (test?.possibleValues() != setOf(0)) B else null

    init {
        zero?.let { it.refCount += 1 }
        nonzero?.let { it.refCount += 1 }
        test?.let { it.refCount += 1 }
    }

    override fun possibleValues() =
        if (test.possibleValues() == setOf(0)) zero?.possibleValues()
        else if (test.canBeZero() == false) nonzero?.possibleValues()
        else nonzero?.possibleValues()?.let { zero?.possibleValues()?.plus(it) }
    override fun canBeZero() =
        if (test?.possibleValues() == setOf(0)) zero?.canBeZero() ?: true
        else if (test?.canBeZero() == false) nonzero?.canBeZero() ?: true
        else !(zero?.canBeZero() == false && nonzero?.canBeZero() == false)

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        val zeroLabel = Label()
        return StackManipulation.Compound(
            getRegister(c),
            JumpIfZero(zeroLabel),
            setRegister(a, getRegister(b)),
            SetLabel(zeroLabel)
        ).apply(mv, context)
    }
}

class LOAD(operation: Int, touched: Array<RegOut>): RegOut(operation, touched) {
    val array = B
    val offset = C

    init {
        array?.let { it.refCount += 1 }
        offset?.let { it.refCount += 1 }
    }

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return StackManipulation.Compound(
            getArrays,
            getRegister(b),
            arrayList_get,
            getRegister(c),
            setRegister(a, ArrayAccess.INTEGER.load())
        ).apply(mv, context)
    }
}

class STORE(operation: Int, touched: Array<RegOut>, val nextPos: Int): Operation(operation, touched) {
    val array = A
    val offset = B
    val value = C

    val exposes = touched.clone()
    var finalPos: Int = -1

    init {
        array?.let { it.refCount += 1 }
        offset?.let { it.refCount += 1 }
        value?.let { it.refCount += 1 }
    }

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return StackManipulation.Compound(
            getArrays,
            getRegister(a),
            arrayList_get,
            getRegister(b),
            getRegister(c),
            ArrayAccess.INTEGER.store()
        ).apply(mv, context)
    }
}

class ADD(operation: Int, touched: Array<RegOut>): RegOut(operation, touched) {
    val left = B
    val right = C

    init {
        left?.let { it.refCount += 1 }
        right?.let { it.refCount += 1 }
    }

    override fun possibleValues() = right?.possibleValues()?.let { left?.possibleValues()?.flatMap { l -> it.map { r -> l + r } } }?.toSet()
    override fun canBeZero() = possibleValues()?.contains(0) ?: true

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return StackManipulation.Compound(
            getRegister(b),
            getRegister(c),
            setRegister(a, Addition.INTEGER)
        ).apply(mv, context)
    }
}

class MUL(operation: Int, touched: Array<RegOut>): RegOut(operation, touched) {
    val left = B
    val right = C

    init {
        left?.let { it.refCount += 1 }
        right?.let { it.refCount += 1 }
    }

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return StackManipulation.Compound(
            getRegister(b),
            getRegister(c),
            setRegister(a, Multiplication.INTEGER)
        ).apply(mv, context)
    }
}

class DIV(operation: Int, touched: Array<RegOut>): RegOut(operation, touched) {
    val left = B
    val right = C

    init {
        left?.let { it.refCount += 1 }
        right?.let { it.refCount += 1 }
    }

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return StackManipulation.Compound(
            getRegister(b),
            UInt2Long,
            getRegister(c),
            UInt2Long,
            setRegister(a, DivideLong2UInt)
        ).apply(mv, context)
    }
}

class NAND(operation: Int, touched: Array<RegOut>): RegOut(operation, touched) {
    val left = B
    val right = C

    init {
        left?.let { it.refCount += 1 }
        right?.let { it.refCount += 1 }
    }

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return StackManipulation.Compound(
            getRegister(b),
            getRegister(c),
            setRegister(a, NotAnd)
        ).apply(mv, context)
    }
}

object EXIT: Operation(0, Array<RegOut>(8) { JunkReg }) {

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return cleanExit.apply(mv, context)
    }
}

class NEW(operation: Int, touched: Array<RegOut>): RegOut(operation, touched) {
    val size = C

    init {
        size?.let { it.refCount += 1 }
    }

    override fun canBeZero() = false

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return setRegister(b, allocate(getRegister(c))).apply(mv, context)
    }
}

class FREE(operation: Int, touched: Array<RegOut>): Operation(operation, touched) {
    val array = C

    init {
        array?.let { it.refCount += 1 }
    }

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return free(getRegister(c)).apply(mv, context)
    }
}

class OUTPUT(operation: Int, touched: Array<RegOut>): Operation(operation, touched) {
    val value = C

    init {
        value?.let { it.refCount += 1 }
    }

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return output(getRegister(c)).apply(mv, context)
    }
}

class INPUT(operation: Int, touched: Array<RegOut>): RegOut(operation, touched) {
    val exposes = touched.clone()

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return StackManipulation.Compound(
            setRegister(c, input)
        ).apply(mv, context)
    }
}

class JUMP(operation: Int, touched: Array<RegOut>): Operation(operation, touched) {
    val array = B
    val offset = C

    val exposes = touched.clone()

    init {
        array?.let { it.refCount += 1 }
        offset?.let { it.refCount += 1 }
    }

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        val jump = StackManipulation.Compound(
            setFinger(getRegister(c)),
            MethodReturn.VOID
        )
        val check = if (array?.possibleValues() == setOf(0)) {
            jump
        } else {
            StackManipulation.Compound(
                doCloneArray(getRegister(b)),
                jump
            )
        }
        return StackManipulation.Compound(check).apply(mv, context)
    }
}

class CONST(operation: Int, touched: Array<RegOut>): RegOut(operation, touched) {
    override fun canBeZero() = (operation and 0x1ffffff) == 0
    override fun possibleValues() = setOf(operation and 0x1ffffff)

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return setRegister(d, IntegerConstant.forValue(operation and 0x1ffffff)).apply(mv, context)
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
        else -> "Illegal"
    }
}

fun compileFragment(um: UM): Fragment {
    val a0 = um.arrays[0]
    val canBeZero = BooleanArray(8) { true }
    val possibleValues = Array<Set<Int>?>(8) { null }
    val readFrom = BooleanArray(8) { false }
    val writtenTo = BooleanArray(8) { false }
    val code = mutableListOf<Operation>()
    var pos = um.finger
    val touched = Array<RegOut>(8) { JunkReg }
    // code += (0..7).map { GET(it, touched) }
    decode@
    while (true) {
        val operation = a0[pos]
        System.err.println("$pos: ${decode(operation)}")
        pos += 1
        when (operation ushr 28) {
            0 -> code += IF(operation, touched)
            1 -> code += LOAD(operation, touched)
            2 -> code += STORE(operation, touched, pos)
            3 -> code += ADD(operation, touched)
            4 -> code += MUL(operation, touched)
            5 -> code += DIV(operation, touched)
            6 -> code += NAND(operation, touched)
            7 -> { code += EXIT; break@decode }
            8 -> code += NEW(operation, touched)
            9 -> code += FREE(operation, touched)
            10 -> code += OUTPUT(operation, touched)
            11 -> code += INPUT(operation, touched)
            12 -> { code += JUMP(operation, touched); break@decode }
            13 -> code += CONST(operation, touched)
            else -> throw IllegalArgumentException()
        }
    }

    code.forEach { when (it) {
        is STORE -> {
            it.finalPos = pos
            if ((it.array?.canBeZero() ?: true) &&
                    (it.offset?.possibleValues()?.intersect(um.finger until pos)?.isNotEmpty() ?: true)) {
                it.exposes.forEach { it?.exposed = true }
            }
        }
        is INPUT -> it.exposes.forEach { it?.exposed = true }
        is JUMP -> it.exposes.forEach { it?.exposed = true }
    } }

    return assembleFragment(code, pos, um)
}

fun assembleFragment(code: List<Operation>, finalPos: Int, um: UM): Fragment {
/*
    val locals = code
            .flatMap { it.toList() }
            .filter { (it is RegOut) && it.refCount > 1 }
            .distinct()

    locals.forEachIndexed { index, op -> (op as RegOut).tmpIndex = index + 2 }
    */

    return ByteBuddy()
            .subclass(Fragment::class.java)
            .method(named("getStart")).intercept(FixedValue.value(um.finger))
            .method(named("getEnd")).intercept(FixedValue.value(finalPos))
            .method(named("run")).intercept(object: Implementation {
                override fun prepare(instrumentedType: InstrumentedType) = instrumentedType
                override fun appender(target: Implementation.Target?): ByteCodeAppender? {
                    return object: ByteCodeAppender {
                        override fun apply(mv: MethodVisitor, context: Implementation.Context, method: MethodDescription): ByteCodeAppender.Size {
                            return ByteCodeAppender.Size(
                                    code.fold(0) { acc, op -> Math.max(acc, op.apply(mv, context).getMaximalSize()) },
                                    3 /* locals.size + 3 */
                            )
                        }
                    }
                }
            })
            .make()
            .load(Fragment::class.java.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
            .getLoaded()
            .newInstance()
}

class InterruptedFragmentException(): RuntimeException()
class CleanExitException(): RuntimeException()

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

    var terminalOut: PrintWriter? = null
    var terminalIn: LineReader? = null
    var pendingLine = ""

    val fragments: SortedMap<Int, Fragment> = TreeMap<Int, Fragment>()
    var fragLookup = 0L
    var fragCompile = 0L
    var fragFailure = 0L
    var fragRun = 0L
    var fragInvalidate = 0L

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
            val ti = LineReaderBuilder.builder().terminal(terminal).build()
            terminalIn = ti
            val map = ti.getKeyMaps().get(LineReader.MAIN)!!
            val binding = object: Widget {
                override fun apply(): Boolean { dumpState(); return true }
            }
            map.bind(binding, KeyMap.ctrl('G'))

            terminalOut = terminal.writer()

            try {
                while (true) {
                    System.err.println("TRACE: Starting fragment at $finger with [${registers.joinToString(", ") { it.toString() }}]")
                    fragLookup += 1
                    val fragment = fragments.computeIfAbsent(finger) {
                        fragCompile += 1
                        try {
                            compileFragment(this)
                        } catch (e: Exception) {
                            fragFailure += 1
                            System.err.println("compile failed:")
                            e.printStackTrace()
                            null
                        }
                    }
                    if (fragment != null) {
                        fragRun += 1
                        try {
                            fragment.run(this, registers)
                        } catch (e: InterruptedFragmentException) { /* NOTHING */ }
                    } else {
                        interpreter@
                        while (true) {
                            operator = arrays[0][finger]
                            // println("finger: $finger  operator: ${java.lang.Integer.toHexString(operator)}")
                            System.err.println(decode(operator))
                            finger += 1
                            when (operator ushr 28) {
                                0 -> if (C != 0) A = B
                                1 -> A = arrays[B][C]
                                2 -> {
                                    arrays[A][B] = C
                                    if (A == 0) clearCorruptedFragments(0, B, C, -1, -1)
                                }
                                3 -> A = B + C
                                4 -> A = (B.toUInt() * C.toUInt()).toInt()
                                5 -> A = (B.toUInt() / C.toUInt()).toInt()
                                6 -> A = (B and C).inv()
                                7 -> throw CleanExitException()
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
                                10 -> output(C)
                                11 -> C = input()
                                12 -> {
                                    if (B != 0) {
                                        fragments.clear()
                                        arrays[0] = arrays[B].clone()
                                    }
                                    finger = C
                                    break@interpreter
                                }
                                13 -> D = V
                                else -> { System.err.println("Illegal operation ${operator ushr 28}"); System.exit(1) }
                            }
                        }
                    }
                }
            } catch (e: CleanExitException) {}
            System.err.println("Fragments:")
            System.err.println("  Lookup:     $fragLookup")
            System.err.println("  Compile:    $fragCompile  (${fragCompile * 100 / fragLookup}%)")
            System.err.println("  Failure:    $fragFailure  (${fragFailure * 100 / fragLookup}%)")
            System.err.println("  Run:        $fragRun  (${fragRun * 100 / fragLookup}%)")
            System.err.println("  Invalidate: $fragInvalidate")
        }
    }

    fun doIfAssign(a: Int, b: Int, c: Int) = if (c == 0) a else b

    fun doCloneArray(which: Int) {
        if (which != 0) {
            fragments.clear()
            arrays[0] = arrays[which].clone()
        }
    }

    fun clearCorruptedFragments(array: Int, offset: Int, value: Int, nextPos: Int, finalPos: Int) {
        if (array == 0) {
            System.err.println("TRACE: Setting $array[$offset] = $value")
            val iter = fragments.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.key > offset) break
                if (entry.value.end >= offset) {
                    fragInvalidate += 1
                    iter.remove()
                }
            }
            if (offset >= nextPos && offset <= finalPos) {
                finger = nextPos
                throw InterruptedFragmentException()
            }
        }
    }

    fun cleanExit() {
        throw CleanExitException()
    }

    fun allocate(size: Int): Int {
        val fresh = if (size == 0) blank else IntArray(size)
        if (available.size == 0) {
            val which = arrays.size
            arrays += fresh
            return which
        } else {
            val which = available.removeLast()
            arrays[which] = fresh
            return which
        }
    }

    fun free(which: Int) {
        arrays[which] = blank
        available.add(which)
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
            out.writeInt(available.size)
            available.forEach(out::writeInt)
        }
        println("Saved")
    }
}
