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

open class StackOps {
    var traceFrag = false
    var traceSetReg = false
    var traceSetMem = false
    var traceOp = false
    var classSave = false

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
    val getFinger = invokeUMMethod("getFinger")
    fun setFinger(where: StackManipulation) = invokeUMMethod("setFinger", where)
    val cleanExit = invokeUMMethod("cleanExit")
    fun allocate(size: StackManipulation) = invokeUMMethod("allocate", size)
    fun free(which: StackManipulation) = invokeUMMethod("free", which)
    val input = invokeUMMethod("input")
    fun output(what: StackManipulation) = invokeUMMethod("output", what)
    fun clearCorruptedFragments(offset: StackManipulation, nextPos: Int, finalPos: Int) =
        invokeUMMethod("clearCorruptedFragments", offset, IntegerConstant.forValue(nextPos), IntegerConstant.forValue(finalPos))
    fun clearCorruptedFragments(offset: StackManipulation, nextPos: Int) =
        invokeUMMethod("clearCorruptedFragments", offset, IntegerConstant.forValue(nextPos), getFragmentEnd)
    fun doCloneArray(which: StackManipulation) = invokeUMMethod("doCloneArray", which)
    fun traceFrag(pos: Int) = if (traceFrag) invokeUMMethod("traceFrag", IntegerConstant.forValue(pos)) else StackManipulation.Trivial.INSTANCE
    fun traceOp(pos: Int, op: Int, aPos: Int, bPos: Int, cPos: Int) = if (traceOp) invokeUMMethod("traceOp", IntegerConstant.forValue(pos), IntegerConstant.forValue(op), IntegerConstant.forValue(aPos), IntegerConstant.forValue(bPos), IntegerConstant.forValue(cPos)) else StackManipulation.Trivial.INSTANCE
    fun traceSetReg(which: Int, pos: Int) = if (traceSetReg) invokeUMMethod("traceSetReg", IntegerConstant.forValue(which), IntegerConstant.forValue(pos)) else StackManipulation.Trivial.INSTANCE
    fun traceSetMem(array: StackManipulation, offset: StackManipulation, value: StackManipulation) = if (traceSetMem) invokeUMMethod("traceSetMem", array, offset, value) else StackManipulation.Trivial.INSTANCE

    val clear = MethodInvocation.invoke(MethodDescription.ForLoadedMethod(SortedMap::class.java.getMethod("clear")))
    val clone = MethodInvocation.invoke(MethodDescription.ForLoadedMethod(Object::class.java.getDeclaredMethod("clone")))
    val arrayList_get = StackManipulation.Compound(
        MethodInvocation.invoke(MethodDescription.ForLoadedMethod(java.util.List::class.java.getDeclaredMethod("get", Int::class.java))),
        TypeCasting.to(TypeDescription.ForLoadedType(IntArray::class.java))
    )
    val getFragmentEnd = 
        StackManipulation.Compound(
            MethodVariableAccess.REFERENCE.loadFrom(0),
            MethodInvocation.invoke(
                MethodDescription.ForLoadedMethod(Fragment::class.java.getMethod("getEnd"))
            )
        )
}

data class InternalExposure(val code: StackManipulation, val locals: Int, val size: Int, val stack: Stack)

class Ref private constructor(
    val nextPos: Int,
    val op: Int,
    a_param: Ref? = null,
    b_param: Ref? = null,
    c_param: Ref? = null,
) {
    val a: Ref = a_param ?: this
    val b: Ref = b_param ?: this
    val c: Ref = c_param ?: this
    var count: Int = 0
    var local: Int = -1
    var exposes: Array<Ref> = fetches

    companion object: StackOps() {
        val fetches = Array<Ref>(8) { Ref(-1, (15 shl 28) + it) }
    }

    constructor(pos: Int, op: Int, touched: Array<Ref>): this(
        pos + 1,
        op,
        touched[(op ushr 6) and 7],
        touched[(op ushr 3) and 7],
        touched[ op         and 7]
    ) {
        regOut(op)?.let { touched[it] = this }
        when (op ushr 28) {
            2, 9, 11, 12, 14 -> {
                exposes = touched.clone()
                touched.forEach { it.mark() }
            }
            10 -> { mark() }
            else -> { /* NOTHING */ }
        }
    }

    fun needsExposure() = (op ushr 28) != 7 && exposes == fetches

    fun mark() {
        count += 1
        if (count == 1) {
            when (op ushr 28) {
                0             -> { a.mark(); b.mark(); c.mark() }
                1, 3, 4, 5, 6 -> {           b.mark(); c.mark() }
                8, 10         -> {                     c.mark() }
                12 -> { markJumpChecks(c) }
                else -> { /* NOTHING */ }
            }
        }
    }

    fun markJumpChecks(c: Ref) {
        if ((c.op ushr 28) == 0 && c.possibleValues() != null) {
            c.c.mark()
            markJumpChecks(c.a)
            markJumpChecks(c.b)
        }
    }

    fun hasSideEffects() = when (op ushr 28) {
        2, 7, 9, 10, 11, 12, 14 -> true
        else -> false
    }

    fun build(stack: Stack, inLocals: Int, remember: Boolean, labels: Map<Int, Label>, jumpLabel: Label): Triple<StackManipulation, /* outLocals */ Int, /* sizeEstimate */ Int> {

        fun buildExposes(): Triple<StackManipulation, /* outLocals */ Int, /* sizeEstimate */ Int> {
            val buildUp = exposes.foldIndexed(
                InternalExposure(StackManipulation.Trivial.INSTANCE, inLocals, 0, stack)
            ) { n, a, f ->
                if (f == fetches[n] || f == this) a
                else {
                   val (c, l, s) = f.build(a.stack + "[I" + Opcodes.INTEGER, a.locals, remember, labels, jumpLabel)
                   InternalExposure(StackManipulation.Compound(
                       a.code,
                       getRegisters,
                       IntegerConstant.forValue(n),
                       c
                   ), l, a.size + s + 4, a.stack + "[I" + Opcodes.INTEGER + Opcodes.INTEGER)
                }
            }
            val tearDown = exposes.foldRightIndexed(buildUp.code) { n, f, a ->
                if (f == fetches[n] || f == this) a
                else {
                   StackManipulation.Compound(
                       a,
                       ArrayAccess.INTEGER.store(),
                       traceSetReg(n, f.nextPos - 1),
                   )
                }
            }
            return Triple(tearDown, buildUp.locals, buildUp.size)
        }

        fun buildIf(): Triple<StackManipulation, /* outLocals */ Int, /* sizeEstimate */ Int> {
            val zero = Label()
            val end = Label()
            val (cc, cl, cs) = c.build(stack, inLocals, remember, labels, jumpLabel)
            val (ac, al, aS) = a.build(stack, cl, false, labels, jumpLabel)
            val (bc, bl, bs) = b.build(stack, cl, false, labels, jumpLabel)
            return Triple(StackManipulation.Compound(
                cc,
                JumpIfZero(zero),
                bc,
                JumpAlways(end),
                SetLabel(zero, stack, cl),
                ac,
                SetLabel(end, stack + Opcodes.INTEGER, cl),
                IntegerConstant.forValue(0),
                Removal.SINGLE,
                traceOp(nextPos - 1, op, a.nextPos - 1, b.nextPos - 1, c.nextPos - 1),
            ), cl, aS + bs + cs + 8)
        }

        fun buildLoad(): Triple<StackManipulation, /* outLocals */ Int, /* sizeEstimate */ Int> {
            val (bc, bl, bs) = b.build(stack + "java/util/List", inLocals, remember, labels, jumpLabel)
            val (cc, cl, cs) = c.build(stack + "[I", bl, remember, labels, jumpLabel)
            return Triple(StackManipulation.Compound(
                getArrays,
                bc,
                arrayList_get,
                cc,
                ArrayAccess.INTEGER.load(),
                traceOp(nextPos - 1, op, a.nextPos - 1, b.nextPos - 1, c.nextPos - 1),
            ), cl, bs + cs + 8)
        }

        fun buildStore(): Triple<StackManipulation, /* outLocals */ Int, /* sizeEstimate */ Int> {
            val (ec, el, es) = buildExposes()
            val check = Label()
            return Triple(StackManipulation.Compound(
                ec,
                getArrays,
                getRegister((op ushr 6) and 7),
                Dup_x1,
                arrayList_get,
                getRegister((op ushr 3) and 7),
                Dup_x2,
                getRegister(op and 7),
                ArrayAccess.INTEGER.store(),
                traceSetMem(
                    getRegister((op ushr 6) and 7),
                    getRegister((op ushr 3) and 7),
                    getRegister(op and 7)
                ),
                traceOp(nextPos - 1, op, a.nextPos - 1, b.nextPos - 1, c.nextPos - 1),
                JumpIfNotZero(check),
                clearCorruptedFragments(Swap, nextPos),
                IntegerConstant.forValue(0),
                SetLabel(check, stack + Opcodes.INTEGER, 3),
                Removal.SINGLE
            ), el, 38 + es)
        }

        fun buildAdd(): Triple<StackManipulation, /* outLocals */ Int, /* sizeEstimate */ Int> {
            val (bc, bl, bs) = b.build(stack, inLocals, remember, labels, jumpLabel)
            val (cc, cl, cs) = c.build(stack + Opcodes.INTEGER, bl, remember, labels, jumpLabel)
            return Triple(StackManipulation.Compound(
                bc,
                cc,
                Addition.INTEGER,
                traceOp(nextPos - 1, op, a.nextPos - 1, b.nextPos - 1, c.nextPos - 1),
            ), cl, bs + cs + 1)
        }

        fun buildMul(): Triple<StackManipulation, /* outLocals */ Int, /* sizeEstimate */ Int> {
            val (bc, bl, bs) = b.build(stack, inLocals, remember, labels, jumpLabel)
            val (cc, cl, cs) = c.build(stack + Opcodes.INTEGER, bl, remember, labels, jumpLabel)
            return Triple(StackManipulation.Compound(
                bc,
                cc,
                Multiplication.INTEGER,
                traceOp(nextPos - 1, op, a.nextPos - 1, b.nextPos - 1, c.nextPos - 1),
            ), cl, bs + cs + 1)
        }

        fun buildDiv(): Triple<StackManipulation, /* outLocals */ Int, /* sizeEstimate */ Int> {
            val (bc, bl, bs) = b.build(stack, inLocals, remember, labels, jumpLabel)
            val (cc, cl, cs) = c.build(stack + Opcodes.LONG, bl, remember, labels, jumpLabel)
            return Triple(StackManipulation.Compound(
                bc,
                UInt2Long,
                cc,
                UInt2Long,
                DivideLong2UInt,
                traceOp(nextPos - 1, op, a.nextPos - 1, b.nextPos - 1, c.nextPos - 1),
            ), cl, bs + cs + 12)
        }

        fun buildNand(): Triple<StackManipulation, /* outLocals */ Int, /* sizeEstimate */ Int> {
            val (bc, bl, bs) = b.build(stack, inLocals, remember, labels, jumpLabel)
            val (cc, cl, cs) = c.build(stack + Opcodes.INTEGER, bl, remember, labels, jumpLabel)
            return Triple(StackManipulation.Compound(
                bc,
                cc,
                NotAnd,
                traceOp(nextPos - 1, op, a.nextPos - 1, b.nextPos - 1, c.nextPos - 1),
            ), cl, bs + cs + 3)
        }

        fun buildHalt(): Triple<StackManipulation, /* outLocals */ Int, /* sizeEstimate */ Int> {
            return Triple(StackManipulation.Compound(
                traceOp(nextPos - 1, op, a.nextPos - 1, b.nextPos - 1, c.nextPos - 1),
                cleanExit,
                MethodReturn.VOID
            ), inLocals, 5)
        }

        fun buildNew(): Triple<StackManipulation, /* outLocals */ Int, /* sizeEstimate */ Int> {
            val (cc, cl, cs) = c.build(stack + "com/wolfskeep/icfp2006_2020/UM", inLocals, remember, labels, jumpLabel)
            return Triple(StackManipulation.Compound(
                allocate(cc),
                traceOp(nextPos - 1, op, a.nextPos - 1, b.nextPos - 1, c.nextPos - 1),
            ), cl, cs + 4)
        }

        fun buildFree(): Triple<StackManipulation, /* outLocals */ Int, /* sizeEstimate */ Int> {
            val (ec, el, es) = buildExposes()
            return Triple(StackManipulation.Compound(
                ec,
                free(getRegister(op and 7)),
                traceOp(nextPos - 1, op, a.nextPos - 1, b.nextPos - 1, c.nextPos - 1),
            ), el, es + 9)
        }

        fun buildOutput(): Triple<StackManipulation, /* outLocals */ Int, /* sizeEstimate */ Int> {
            val (cc, cl, cs) = c.build(stack + "com/wolfskeep/icfp2006_2020/UM", inLocals, remember, labels, jumpLabel)
            return Triple(StackManipulation.Compound(
                output(cc),
                traceOp(nextPos - 1, op, a.nextPos - 1, b.nextPos - 1, c.nextPos - 1),
            ), cl, cs + 4)
        }

        fun buildInput(): Triple<StackManipulation, /* outLocals */ Int, /* sizeEstimate */ Int> {
            val (ec, el, es) = buildExposes()
            return Triple(StackManipulation.Compound(
                ec,
                setRegister(op and 7, input),
                traceOp(nextPos - 1, op, a.nextPos - 1, b.nextPos - 1, c.nextPos - 1),
            ), el, 8 + es)
        }

        fun buildJumpChecks(target: Ref): Triple<StackManipulation, Int, Int> {
            val tpv = target.possibleValues()
            if (tpv == null) {
                return Triple(StackManipulation.Compound(
                    getRegister(op and 7),
                    JumpAlways(jumpLabel)
                ), inLocals, 7)
            }

            if (tpv.size == 1) {
                val where = labels[tpv.first()]
                if (where != null) {
                    if (tpv.first() != nextPos) {
                        return Triple(StackManipulation.Compound(
//                            output(IntegerConstant.forValue(33)),
                            JumpAlways(where)
                        ), inLocals, 3)
                    } else {
                        // Avoid doubled labels
                        return Triple(StackManipulation.Compound(
//                            output(IntegerConstant.forValue(61)),
                            IntegerConstant.forValue(0),
                            Removal.SINGLE
                        ), inLocals, 2)
                    }
                } else {
                    return Triple(StackManipulation.Compound(
//                        output(IntegerConstant.forValue(34)),
                        IntegerConstant.forValue(tpv.first()),
                        JumpAlways(jumpLabel)
                    ), inLocals, 6)
                }
            }

            if ((target.op ushr 28) == 0 && target.nextPos == nextPos - 1) {
                val apv = target.a.possibleValues()
                val bpv = target.b.possibleValues()
                val (cc, cl, cs) = target.c.build(stack, inLocals, remember, labels, jumpLabel)

                if (apv?.size == 1 && bpv?.size == 1 && labels[apv.first()] != null && labels[bpv.first()] != null) {
                    if (apv.first() == nextPos) {
                        return Triple(StackManipulation.Compound(
//                            output(IntegerConstant.forValue(93)),
                            cc,
                            JumpIfNotZero(labels[bpv.first()]!!)
                        ), inLocals, cs + 3)
                    }
                    if (bpv.first() == nextPos) {
                        return Triple(StackManipulation.Compound(
//                            output(IntegerConstant.forValue(91)),
                            cc,
                            JumpIfZero(labels[apv.first()]!!)
                        ), inLocals, cs + 3)
                    }
                }

                if (apv?.size == 1 && labels[apv.first()] != null && apv.first() != nextPos) {
                    val (bc, bl, bs) = buildJumpChecks(target.b)
                    return Triple(StackManipulation.Compound(
//                        output(IntegerConstant.forValue(60)),
                        cc,
                        JumpIfZero(labels[apv.first()]!!),
                        bc
                    ), inLocals, cs + bs + 3)
                }
                if (bpv?.size == 1 && labels[bpv.first()] != null && bpv.first() != nextPos) {
                    val (ac, al, aS) = buildJumpChecks(target.a)
                    return Triple(StackManipulation.Compound(
//                        output(IntegerConstant.forValue(62)),
                        cc,
                        JumpIfNotZero(labels[bpv.first()]!!),
                        ac
                    ), inLocals, cs + aS + 3)
                }

/*
                val (ac, al, aS) = buildJumpChecks(target.a)
                val (bc, bl, bs) = buildJumpChecks(target.b)
                val label = Label()
                if (apv?.contains(nextPos) ?: false) {
                    return Triple(StackManipulation.Compound(
//                        output(IntegerConstant.forValue(40)),
                        cc,
                        JumpIfZero(label),
                        bc,
                        SetLabel(label, stack, inLocals),
                        ac
                    ), inLocals, aS + bs + cs + 3)
                } else {
                    return Triple(StackManipulation.Compound(
//                        output(IntegerConstant.forValue(41)),
                        cc,
                        JumpIfNotZero(label),
                        ac,
                        SetLabel(label, stack, inLocals),
                        bc
                    ), inLocals, aS + bs + cs + 3)
                }
*/
            }
            return Triple(StackManipulation.Compound(
//                output(IntegerConstant.forValue(126)),
                getRegister(op and 7),
                JumpAlways(jumpLabel)
            ), inLocals, 7)
        }

        fun buildJump(): Triple<StackManipulation, /* outLocals */ Int, /* sizeEstimate */ Int> {
            val (ec, el, es) = buildExposes()
            val (jc, jl, js) = buildJumpChecks(c)

            if (b.possibleValues() == setOf(0)) {
                return Triple(StackManipulation.Compound(ec, jc), el, es + js)
            } else {
                val zero = Label()
                return Triple(
                    StackManipulation.Compound(
                        ec,
                        traceOp(nextPos - 1, op, a.nextPos - 1, b.nextPos - 1, c.nextPos - 1),
                        getRegister((op ushr 3) and 7),
                        JumpIfZero(zero),
                        doCloneArray(getRegister((op ushr 3) and 7)),
                        setFinger(getRegister(op and 7)),
                        MethodReturn.VOID,
                        SetLabel(zero, Stack.empty, el),
                        jc
                    ),
                    el,
                    js + 23 + es
                )
            }
        }

        fun buildConst(): Triple<StackManipulation, /* outLocals */ Int, /* sizeEstimate */ Int> {
            // Don't bother putting this in a local variable
            count = 1
            return Triple(StackManipulation.Compound(
                IntegerConstant.forValue(op and 0x1ffffff),
                traceOp(nextPos - 1, op, a.nextPos - 1, b.nextPos - 1, c.nextPos - 1),
            ), inLocals, 3)
        }

        fun buildReg(): Triple<StackManipulation, /* outLocals */ Int, /* sizeEstimate */ Int> {
            // Don't bother putting this in a local variable
            count = 1
            return Triple(StackManipulation.Compound(
                getRegister(op and 7),
                traceOp(nextPos - 1, op, a.nextPos - 1, b.nextPos - 1, c.nextPos - 1),
            ), inLocals, 4)
        }

        if (local > 0) {
            return Triple(MethodVariableAccess.INTEGER.loadFrom(local), inLocals, 2)
        } else {
            val (chunk, outLocals, size) = when (op ushr 28) {
                0 -> buildIf()
                1 -> buildLoad()
                2 -> buildStore()
                3 -> buildAdd()
                4 -> buildMul()
                5 -> buildDiv()
                6 -> buildNand()
                7 -> buildHalt()
                8 -> buildNew()
                9 -> buildFree()
                10 -> buildOutput()
                11 -> buildInput()
                12 -> buildJump()
                13 -> buildConst()
                14 -> buildExposes()
                15 -> buildReg()
                else -> throw IllegalStateException("processing bad instruction")
            }
            if (remember && count > 1) {
                local = outLocals
                return Triple(StackManipulation.Compound(
                    chunk,
                    Duplication.SINGLE,
                    MethodVariableAccess.INTEGER.storeAt(local)
                ), outLocals + 1, size + 3)
            } else {
                return Triple(StackManipulation.Compound(
                    chunk
                ), outLocals, size)
            }
        }
    }
}

fun Ref?.possibleValues(): Set<Int>? = if (this == null) null else when (op ushr 28) {
    0 -> a.possibleValues()?.let { b.possibleValues()?.plus(it) }
    13 -> setOf(op and 0x1ffffff)
    else -> null
}

class Block(
    val code: IntArray,
    val start: Int,
    val stop: Int,
    labels: Map<Int, Label>,
    jumpLabel: Label
) {
    val touched = Ref.Companion.fetches.clone()
    val refs = code.asList().subList(start, stop).mapIndexed { off, op ->
        Ref(start + off, op, touched)
    }
    val refsPlusExposed = if (refs.last().needsExposure()) {
        refs + Ref(stop, 14 shl 28, touched)
    } else {
        refs
    }
    val canFallThrough = when (code[stop - 1] ushr 28) {
        7, 12 -> false
        else -> true
    }

    val triple =
        refsPlusExposed
            .filter { it.hasSideEffects() }
            .fold(Triple(StackManipulation.Trivial.INSTANCE as StackManipulation, 3, 0)) { (ac, al, aS), f ->
                val (bc, bl, bs) = f.build(Stack.empty, al, true, labels, jumpLabel)
                Triple(StackManipulation.Compound(ac, bc), bl, aS + bs)
            }
    val stackCode get() = triple.first
    val maxLocals get() = triple.second
    val size      get() = triple.third
}

fun regOut(op: Int): Int? = when (op ushr 28) {
    0, 1, 3, 4, 5, 6 -> (op ushr 6) and 7
    8 -> (op ushr 3) and 7
    11 -> op and 7
    13 -> (op ushr 25) and 7
    else -> null
}

fun canBeZero(code: IntArray, start: Int, stop: Int, which: Int): Boolean {
    var end = stop - 1
    while (end >= start) {
        val op = code[end]
        if (regOut(op) == which) {
            return when (op ushr 28) {
                0, 4 -> canBeZero(code, start, end, op and 7) || canBeZero(code, start, end, (op ushr 3) and 7)
                5 -> canBeZero(code, start, end, (op ushr 3) and 7)
                8 -> false
                13 -> (op and 0x1ffffff) == 0
                else -> true
            }
        }
        end -= 1
    }
    return true
}

fun possibleValues(code: IntArray, start: Int, stop: Int, which: Int): Set<Int>? {
    var end = stop - 1
    while (end >= start) {
        val op = code[end]
        if (regOut(op) == which) {
            return when (op ushr 28) {
                0 -> possibleValues(code, start, end, (op ushr 6) and 7)?.let { a -> possibleValues(code, start, end, (op ushr 3) and 7)?.plus(a) }
                13 -> setOf(op and 0x1ffffff)
                else -> null
            }
        }
        end -= 1
    }
    return null
}

fun findBlocks(code: IntArray, start: Int, stop: Int): Pair<Fragment, Iterable<Int>> {
    val targets = mutableSetOf(start)
    var end = start
    while (targets.contains(end)) {
        val begin = end
        while (end < stop) {
            val op = code[end] ushr 28
            end += 1
            when (op) {
                2, 9, 11 -> { targets += end; break }
                7 -> break
                12 -> {
                    if (canBeZero(code, begin, end, (code[end - 1] ushr 3) and 7)) {
                        possibleValues(code, begin, end, (code[end - 1] and 7))?.let { targets += it }
                    }
                    break
                }
                else -> { /* NOTHING */ }
            }
        }
        // System.err.println("Discovered $begin to $end")
    }
    val sorted = targets.toList().filter { it >= start && it <= end }.sorted()
    val jumpLabel = Label()
    val labels = TreeMap<Int, Label>()
    sorted.associateWithTo(labels) { Label() }
    val blocks = (sorted + end).windowed(2).map { (b, e) -> Block(code, b, e, labels, jumpLabel) }
    val sizes = blocks.map { it.size }.scan(20) { a, b -> a + b + 8 }
    // System.err.println("Found ${blocks.size} blocks with total size ${sizes.last()}")
    val trimmed = if (sizes.last() < 40000) blocks else {
        val sorted2 = sorted.take(sizes.indexOfFirst { it > 40000 })
        labels.clear()
        sorted2.dropLast(1).associateWithTo(labels) { Label() }
        sorted2.windowed(2).map { (b, e) -> Block(code, b, e, labels, jumpLabel) }
    }
    // System.err.println("Trimmed to ${trimmed.size} blocks with total size ${sizes[trimmed.size - 1]}")

    val default = Label()
    val stackCode = StackManipulation.Compound(
        Ref.Companion.getFinger,
        SetLabel(jumpLabel, Stack.empty + Opcodes.INTEGER, 3),
        Duplication.SINGLE,
        Ref.Companion.setFinger(Swap),
        LookupSwitch(default, labels),
        *(trimmed.map { StackManipulation.Compound(
            SetLabel(labels[it.start]!!, Stack.empty, 3),
            Ref.Companion.traceFrag(it.start),
            it.stackCode
        ) }.toTypedArray()),
        if (trimmed.last().canFallThrough)
            Ref.Companion.setFinger(IntegerConstant.forValue(trimmed.last().stop))
        else
            StackManipulation.Trivial.INSTANCE,
        SetLabel(default, Stack.empty, 3),
        MethodReturn.VOID
    )
    val maxLocals = trimmed.map { it.maxLocals }.max()

    val bca = object: ByteCodeAppender {
        override fun apply(mv: MethodVisitor, context: Implementation.Context, desc: MethodDescription): ByteCodeAppender.Size {
            val size = stackCode.apply(mv, context)
            return ByteCodeAppender.Size(size.getMaximalSize(), maxLocals ?: 3)
        }
    }
    try {
        val thang = ByteBuddy()
            .subclass(Fragment::class.java)
            .method(named("getStart")).intercept(FixedValue.value(trimmed.first().start))
            .method(named("getEnd")).intercept(FixedValue.value(trimmed.last().stop))
            .method(named("run")).intercept(object: Implementation {
                override fun prepare(instrumentedType: InstrumentedType) = instrumentedType
                override fun appender(target: Implementation.Target?): ByteCodeAppender? {
                    return bca
                }
            })
            .make()
        if (Ref.Companion.classSave) {
            val map = thang.saveIn(File("classDump"))
            map.forEach { (k, v) -> System.err.println("Assembled: $k: $v") }
        }
        return Pair(thang
            .load(Fragment::class.java.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
            .getLoaded().newInstance(), labels.keys)
    } catch (e: net.bytebuddy.jar.asm.MethodTooLargeException) {
        System.err.println("method too large from ${trimmed.first().start}-${trimmed.last().stop}")
        throw e
    }
}

class Stack private constructor(val array: Array<Any>) {
    val size get() = array.size
    val children = HashMap<Any, Stack>()

    operator fun plus(x: Any): Stack =
        children.computeIfAbsent(x) {
            val a = Arrays.copyOf(array, size + 1)
            a[size] = x
            Stack(a)
        }

    companion object {
        val empty = Stack(arrayOf())
    }
}

class SetLabel(val label: Label, val stack: Stack, val localSize: Int = 3): StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitLabel(label)
        mv.visitFrame(F_NEW, localSize, locals[localSize], stack.size, stack.array)
        return StackManipulation.Size(0, 0)
    }

    companion object {
        val localsBase = arrayOf<Any>(
            "com/wolfskeep/icfp2006_2020/Fragment",
            "com/wolfskeep/icfp2006_2020/UM",
            "[I",
            Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER,
            Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER,
            Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER,
            Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER,
            Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER,
            Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER,
            Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER,
            Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER
        )
        val locals = Array<Array<Any>>(localsBase.size + 1) { localsBase.take(it).toTypedArray() }
    }
}

class LookupSwitch(val default: Label, val table: SortedMap<Int, Label>): StackManipulation {
    override fun isValid() = true
    override fun apply(mv: MethodVisitor, context: Implementation.Context): StackManipulation.Size {
        mv.visitLookupSwitchInsn(default, table.keys.toIntArray(), table.values.toTypedArray())
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

    val checked = mutableSetOf<Int>()

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
                    // System.err.println("TRACE: Starting fragment at $finger with [${registers.joinToString(", ") { it.toString() }}]")
                    fragLookup += 1
                    val fragment = fragments[finger] ?: try {
                        fragCompile += 1
                        val (fragment, targets) = findBlocks(arrays[0], finger, arrays[0].size)
                        fragments += targets.associateWith { fragment }
                        checked.removeAll(checked.filter { it >= finger || it < fragment.end })
                        fragment
                    } catch (e: Exception) {
                        fragFailure += 1
                        System.err.println("compile failed:")
                        e.printStackTrace()
                        null
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
                            // System.err.println(decode(operator))
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
            finally {
                System.err.println("Fragments:")
                System.err.println("  Lookup:     $fragLookup")
                System.err.println("  Compile:    $fragCompile  (${fragCompile * 100 / fragLookup}%)")
                System.err.println("  Failure:    $fragFailure  (${fragFailure * 100 / fragLookup}%)")
                System.err.println("  Run:        $fragRun  (${fragRun * 100 / fragLookup}%)")
                System.err.println("  Invalidate: $fragInvalidate")
            }
        }
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
        fragments.clear()
        checked.clear()
        arrays[0] = arrays[which].clone()
    }

    fun clearCorruptedFragments(offset: Int, nextPos: Int, finalPos: Int) {
        if (checked.contains(offset)) return
        checked.add(offset)
        val iter = fragments.entries.iterator()
        var badFrag: Fragment? = null
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key > offset && entry.value != badFrag) break
            if (entry.value.end > offset) {
                fragInvalidate += 1
                // System.err.println("===> Invalidated ${entry.key}-${entry.value.end}")
                iter.remove()
            }
        }
        if (offset >= nextPos && offset <= finalPos) {
            finger = nextPos
            // System.err.println("===> Interrupted at $nextPos")
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
