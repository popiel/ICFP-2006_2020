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

abstract class Operation(val operation: Int): StackManipulation {
    inline val code get() = operation ushr 28
    inline val a get() = operation shr 6 and 7
    inline val b get() = operation shr 3 and 7
    inline val c get() = operation shr 0 and 7
    inline val d get() = operation shr 25 and 7

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
        fun from(operation: Int, touched: Array<RegOut>, pos: Int): Operation {
            val a = operation shr 6 and 7
            val b = operation shr 3 and 7
            val c = operation shr 0 and 7
            val d = operation shr 25 and 7
            return when (operation ushr 28) {
                0 -> if (touched[c].possibleValues() == setOf(0)) NOP
                    else if (!touched[c].canBeZero()) MOVE(operation, touched[b]).also { touched[a] = it }
                    else IF(operation, touched[c], touched[a], touched[b]).also { touched[a] = it }
                1 -> LOAD(operation, touched[b], touched[c]).also { touched[a] = it }
                2 -> STORE(operation, touched[a], touched[b], touched[c], pos)
                3 -> ADD(operation, touched[b], touched[c]).also { touched[a] = it }
                4 -> MUL(operation, touched[b], touched[c]).also { touched[a] = it }
                5 -> DIV(operation, touched[b], touched[c]).also { touched[a] = it }
                6 -> if (b != c) NAND(operation, touched[b], touched[c]).also { touched[a] = it }
                    else NOTOP(operation, touched[b]).also { touched[a] = it }
                7 -> EXIT
                8 -> NEW(operation, touched[c]).also { touched[b] = it }
                9 -> FREE(operation, touched[c])
                10 -> OUTPUT(operation, touched[c])
                11 -> INPUT(operation).also { touched[c] = it }
                12 -> JUMP(operation, touched[b], touched[c])
                13 -> CONST(operation).also { touched[d] = it }
                else -> throw IllegalArgumentException()
            }
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
        //val getRegisters = invokeUMMethod("getRegisters")
        val getArrays = invokeUMMethod("getArrays")
        val getFragments = invokeUMMethod("getFragments")
        fun setFinger(where: StackManipulation) = invokeUMMethod("setFinger", where)
        val cleanExit = invokeUMMethod("cleanExit")
        fun allocate(size: StackManipulation) = invokeUMMethod("allocate", size)
        fun free(which: StackManipulation) = invokeUMMethod("free", which)
        val input = invokeUMMethod("input")
        fun output(what: StackManipulation) = invokeUMMethod("output", what)
        fun clearCorruptedFragments(offset: StackManipulation, nextPos: Int, finalPos: Int) =
            invokeUMMethod("clearCorruptedFragments", offset, IntegerConstant.forValue(nextPos), IntegerConstant.forValue(finalPos))
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

abstract class RegOut(operation: Int): Operation(operation) {
    var exposed = false
    var refCount = 0
    var scopeStart: Label? = null
    var scopeEnd: Label? = null

    open fun canBeZero(): Boolean = true
    open fun possibleValues(): Set<Int>? = null
}

class SetLabel(val label: Label, val stackSize: Int = 0): StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitLabel(label)
        when (stackSize) {
            0 -> mv.visitFrame(F_SAME, 0, arrayOf(), 0, arrayOf())
            1 -> mv.visitFrame(F_SAME1, 0, arrayOf(), 1, arrayOf(Opcodes.INTEGER))
            else -> mv.visitFrame(F_FULL, 3, arrayOf("com/wolfskeep/icfp2006_2020/Fragment", "com/wolfskeep/icfp2006_2020/UM", "[I"), stackSize, Array<Object>(stackSize) { Opcodes.INTEGER as Object })
        }
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

class GET(val which: Int): RegOut(-1) {
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return StackManipulation.Compound(
            getRegister(which),
            MethodVariableAccess.INTEGER.storeAt(which + 3)
        ).apply(mv, context)
    }
}

class PUT(val which: Int, val source: RegOut): Operation(-1) {
    init { if (!(source is GET)) source.refCount += 1 }
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return setRegister(which, MethodVariableAccess.INTEGER.loadFrom(which + 3))
            .apply(mv, context)
    }
}

object NOP: Operation(0) {
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return StackManipulation.Trivial.INSTANCE.apply(mv, context)
    }
}

class MOVE(operation: Int, val nonzero: RegOut): RegOut(operation) {
    init {
        nonzero.refCount += 1
    }

    override fun possibleValues() = nonzero.possibleValues()
    override fun canBeZero() = nonzero.canBeZero()

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return setRegister(a, getRegister(b)).apply(mv, context)
    }
}

class IF(operation: Int, val test: RegOut, val zero: RegOut, val nonzero: RegOut): RegOut(operation) {
    init {
        zero.refCount += 1
        nonzero.refCount += 1
        test.refCount += 1
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

class LOAD(operation: Int, val array: RegOut, val offset: RegOut): RegOut(operation) {

    init {
        array.refCount += 1
        offset.refCount += 1
    }

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return setRegister(a, StackManipulation.Compound(
            getArrays,
            getRegister(b),
            arrayList_get,
            getRegister(c),
            ArrayAccess.INTEGER.load()
        )).apply(mv, context)
    }
}

class STORE(operation: Int, val array: RegOut, val offset: RegOut, val value: RegOut, val nextPos: Int): Operation(operation) {
    // val exposes = touched.clone()
    var finalPos: Int = -1

    init {
        array.refCount += 1
        offset.refCount += 1
        value.refCount += 1
    }

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        val check = Label()
        val end = Label()
        return StackManipulation.Compound(
            getArrays,
            getRegister(a),
            Dup_x1,
            arrayList_get,
            getRegister(b),
            Dup_x2,
            getRegister(c),
            ArrayAccess.INTEGER.store(),
            JumpIfNotZero(check),
            clearCorruptedFragments(Swap, nextPos, finalPos),
            JumpAlways(end),
            SetLabel(check, 1),
            Removal.SINGLE,
            SetLabel(end)
        ).apply(mv, context)
    }
}

class ADD(operation: Int, val left: RegOut, val right: RegOut): RegOut(operation) {
    init {
        left.refCount += 1
        right.refCount += 1
    }

    override fun possibleValues() = right?.possibleValues()?.let { left?.possibleValues()?.flatMap { l -> it.map { r -> l + r } } }?.toSet()
    override fun canBeZero() = possibleValues()?.contains(0) ?: true

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return setRegister(a, StackManipulation.Compound(
            getRegister(b),
            getRegister(c),
            Addition.INTEGER
        )).apply(mv, context)
    }
}

class MUL(operation: Int, val left: RegOut, val right: RegOut): RegOut(operation) {
    init {
        left.refCount += 1
        right.refCount += 1
    }

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return setRegister(a, StackManipulation.Compound(
            getRegister(b),
            getRegister(c),
            Multiplication.INTEGER
        )).apply(mv, context)
    }
}

class DIV(operation: Int, val left: RegOut, val right: RegOut): RegOut(operation) {
    init {
        left.refCount += 1
        right.refCount += 1
    }

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return setRegister(a, StackManipulation.Compound(
            getRegister(b),
            UInt2Long,
            getRegister(c),
            UInt2Long,
            DivideLong2UInt
        )).apply(mv, context)
    }
}

class NAND(operation: Int, val left: RegOut, val right: RegOut): RegOut(operation) {
    init {
        left.refCount += 1
        right.refCount += 1
    }

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return setRegister(a, StackManipulation.Compound(
            getRegister(b),
            getRegister(c),
            NotAnd
        )).apply(mv, context)
    }
}

class NOTOP(operation: Int, val right: RegOut): RegOut(operation) {
    init {
        right.refCount += 1
    }

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return setRegister(a, StackManipulation.Compound(
            getRegister(b),
            Not
        )).apply(mv, context)
    }
}

object EXIT: Operation(7 shl 28) {
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return StackManipulation.Compound(
            cleanExit,
            MethodReturn.VOID
        ).apply(mv, context)
    }
}

class NEW(operation: Int, val size: RegOut): RegOut(operation) {
    init {
        size.refCount += 1
    }

    override fun canBeZero() = false

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return setRegister(b, allocate(getRegister(c))).apply(mv, context)
    }
}

class FREE(operation: Int, val array: RegOut): Operation(operation) {
    init {
        array.refCount += 1
    }

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return free(getRegister(c)).apply(mv, context)
    }
}

class OUTPUT(operation: Int, val value: RegOut): Operation(operation) {
    init {
        value.refCount += 1
    }

    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return output(getRegister(c)).apply(mv, context)
        // return StackManipulation.Trivial.INSTANCE.apply(mv, context)
    }
}

class INPUT(operation: Int): RegOut(operation) {
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        return setRegister(c, input).apply(mv, context)
    }
}

class JUMP(operation: Int, val array: RegOut, val offset: RegOut): Operation(operation) {
    init {
        array.refCount += 1
        offset.refCount += 1
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

class CONST(operation: Int): RegOut(operation) {
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
    val touched = (0..7).map { GET(it) }.toTypedArray<RegOut>()
    // code += touched
    decode@
    while (true) {
        val operation = a0[pos]
        //System.err.println("$pos: ${decode(operation)}")
        pos += 1
        code += Operation.from(operation, touched, pos)
        val op = operation ushr 28
        if (op == 7 || op == 12) break@decode
    }
    val last = code.last()
    if (last is JUMP) {
        if (last.offset.possibleValues() == null) {
            println("===> possibly unknown jump target")
            /*
            println("---> Fragment:")
            (um.finger until pos).forEach { p ->
                System.err.println("$p: ${decode(a0[p])}")
            }
            */
        }
        if (last.offset.possibleValues()?.contains(pos) ?: false) {
            println("===> conditional jump")
            println("---> Fragment:")
            (um.finger until pos).forEach { p ->
                System.err.println("$p: ${decode(a0[p])}")
            }
        }
    }

    /*
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
    */

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

    val thang = ByteBuddy()
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
    //val map = thang.saveIn(File("classDump"))
    //map.forEach { (k, v) -> System.err.println("Assembled: $k: $v") }
    return thang.getLoaded().newInstance()
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

    var running = true

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
                    //System.err.println("TRACE: Starting fragment at $finger with [${registers.joinToString(", ") { it.toString() }}]")
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
                                    if (A == 0) clearCorruptedFragments(B, -1, -1)
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

    fun clearCorruptedFragments(offset: Int, nextPos: Int, finalPos: Int) {
        val iter = fragments.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key > offset) break
            if (entry.value.end > offset) {
                fragInvalidate += 1
                println("===> Invalidated ${entry.key}-${entry.value.end}")
                iter.remove()
            }
        }
        if (offset >= nextPos && offset <= finalPos) {
            finger = nextPos
            println("===> Interrupted at $nextPos")
            throw InterruptedFragmentException()
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
