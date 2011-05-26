/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.sun.max.vm.compiler.c1x;

import static com.sun.max.vm.MaxineVM.*;
import static com.sun.max.vm.compiler.c1x.C1XTargetMethod.*;
import static com.sun.max.vm.compiler.c1x.ValueCodec.*;

import java.io.*;
import java.util.*;

import com.sun.cri.ci.*;
import com.sun.cri.ri.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.compiler.target.*;

/**
 * The debug info for the stops in a {@link C1XTargetMethod}.
 */
public final class DebugInfo {

    static final int FIRST_FRAME = 1;
    static final int NO_FRAME = 0;

    /**
     * Encoded debug info. This array has three ordered sections:
     * <ol>
     * <li>The frame and register reference maps for the associated target method.
     * The format of this section is described by the following pseudo C declaration:
     * <pre>
     * referenceMaps {
     *     {
     *         u1 frameMap[frameRefMapSize];
     *         u1 regMap[regRefMapSize];
     *     } directCallMaps[numberOfDirectCalls]
     *     {
     *         u1 frameMap[frameRefMapSize];
     *         u1 regMap[regRefMapSize];
     *     } indirectCallMaps[numberOfIndirectCalls]
     *     {
     *         u1 frameMap[frameRefMapSize];
     *         u1 regMap[regRefMapSize];
     *     } safepointMaps[numberOfSafepoints]
     * }
     * </pre>
     * </li>
     * <li>Frame position table (FPT). This is a table mapping frame indexes to the position of the frame encoded (in the following section)
     * for the stop. The prefix of the table is the stop indexes. Following that are indexes of the caller frames. If {@code data.length <= 0xFFFF}
     * then each entry in this table is an unsigned short otherwise each entry is an integer. The size of this
     * table is stored in {@link #fptSize}.</li>
     * <li>Encoded frames.
     * The format of this section is described by {@code frames} in the following pseudo C declarations.
     * All {@code uint}s are encoded with {@link EncodingStream#encodeUInt(int)} and the encoding
     * of {@code value} is specified by {@link ValueCodec#writeValue(EncodingStream, CiValue)}.
     * <pre>
     *     {
     *         uint caller;           // index in FPT of caller frame + 1 (0 means no caller)
     *         uint holder;           // class ID of method holder
     *         union {
     *             method_with_bci;
     *             method_no_bci;
     *         } method              // the ID of method (within holder) and BCI
     *         uint num_locals;
     *         uint num_stack;
     *         uint num_locks;
     *         value[num_locals + num_stack + num_locks] values;
     *     } frames[fptSize]
     *
     *     method_with_bci {
     *         uint method; // the ID of method << 1 | 0
     *         uint bci;    // BCI
     *     }
     *
     *     method_no_bci {
     *         uint method; // the ID of method << 1 | 1 (BCI is -1)
     *     }
     * </pre>
     * </li>
     * </ol>
     */
    final byte[] data;

    /**
     * The number of entries in the FPT.
     */
    final int fptSize;

    /**
     * Encodes an array of debug infos.
     *
     * @param debugInfos an array of debug infos correlated with each stop index
     * @param tm the target method associated with the debug infos
     */
    public DebugInfo(CiDebugInfo[] debugInfos, C1XTargetMethod tm) {
        final HashMap<CiFrame, int[]> framesMap = new HashMap<CiFrame, int[]>();
        final EncodingStream out = new EncodingStream(1024);

        // Reserve space for the reference maps
        int totalRefMapsSize = (tm.totalRefMapSize()) * debugInfos.length;
        out.skip(totalRefMapsSize);

        int index = 0;
        for (CiDebugInfo info : debugInfos) {
            if (info != null) {
                int refmapIndex = index * (tm.totalRefMapSize());
                initRefMap(out.buf, index, refmapIndex, info, tm.frameRefMapSize(), regRefMapSize());
                CiFrame frame = info.frame();
                if (frame != null) {
                    int[] indexes = framesMap.get(frame);
                    if (indexes == null) {
                        framesMap.put(frame, new int[] {index});
                    } else {
                        indexes = Arrays.copyOf(indexes, indexes.length + 1);
                        indexes[indexes.length - 1] = index;
                        framesMap.put(frame, indexes);
                    }
                }
            }
            index++;
        }

        for (CiDebugInfo info : debugInfos) {
            if (info != null && info.frame() != null) {
                index = gatherCallers(info.frame().caller(), framesMap, index);
            }
        }

        fptSize = index;
        final int fpt = out.pos;
        if (!encodeFrames(framesMap, out, 2)) {
            out.seek(fpt, true);
            encodeFrames(framesMap, out, 4);
        }
        this.data = out.toByteArray();

        if (isHosted()) {
            // Test encoding & decoding while offline
            for (int i = 0; i < debugInfos.length; ++i) {
                if (debugInfos[i] != null && debugInfos[i].frame() != null) {
                    CiFrame frame = frameAt(tm, i);
                    CiFrame originalFrame = debugInfos[i].frame();
                    assert frame.equalsIgnoringKind(originalFrame);
                }
            }
        }

        totalDebugInfos++;
        totalDebugInfoBytes += this.data.length;
        totalCode += tm.codeLength();
    }

    /**
     * Encodes a given set of frames and records their positions in the FPT.
     *
     * @param framesMap map from frames to indexes in the FPT
     * @param out the underlying encoding buffer currently positioned where the FPT is to be written
     * @param fps the frame position size (i.e. the size of an entry in the FPT)
     * @return {@code true} if {@code fps != 2} or every element in the underlying buffer can be addressed by an unsigned 16-bit index
     */
    boolean encodeFrames(final HashMap<CiFrame, int[]> framesMap, final EncodingStream out, int fps) {
        // Reserve space for the FPT
        int fpt = out.pos;
        out.skip(fptSize * fps);

        int i = 0;
        for (CiFrame frame : framesMap.keySet()) {
            int framePos = out.pos;

            // Write the frame position in the FPT
            int[] indexes = framesMap.get(frame);
            for (int frameIndex : indexes) {
                int fptEntry = fpt + (frameIndex * fps);
                out.seek(fptEntry, false);
                if (fps == 2) {
                    out.writeShort(framePos);
                } else {
                    out.writeInt(framePos);
                }
            }

            // Write the frame itself
            out.seek(framePos, false);
            CiFrame caller = frame.caller();
            if (caller == null) {
                out.encodeUInt(NO_FRAME);
            } else {
                out.encodeUInt(framesMap.get(caller)[0] + FIRST_FRAME);
            }

            out.encodeUInt(((ClassActor) frame.method.holder()).id);
            int m = ((MethodActor) frame.method).memberIndex() << 1;
            if (frame.bci == -1) {
                out.encodeUInt(m | 1);
            } else {
                out.encodeUInt(m);
                out.encodeUInt(frame.bci);
            }

            out.encodeUInt(frame.numLocals);
            out.encodeUInt(frame.numStack);
            out.encodeUInt(frame.numLocks);

            for (CiValue value : frame.values) {
                if (isHosted()) {
                    // Test codec while offline
                    CiValue v = testCodec(value);
                    if (!value.equalsIgnoringKind(v)) {
                        writeValue(out, value);
                        assert false : "value: " + value + ", v: " + v;
                    }
                }
                writeValue(out, value);
            }

            if (fps == 2 && (out.pos & 0xFFFF) != out.pos) {
                return false;
            }
            i++;
        }
        return true;
    }

    private static int gatherCallers(CiFrame callerFrame, HashMap<CiFrame, int[]> framesMap, int nextIndex) {
        if (callerFrame == null || framesMap.containsKey(callerFrame)) {
            return nextIndex;
        }
        nextIndex = gatherCallers(callerFrame.caller(), framesMap, nextIndex);
        framesMap.put(callerFrame, new int[] {nextIndex});
        return ++nextIndex;
    }

    private static void initRefMap(byte[] data, int index, int refmapIndex, CiDebugInfo debugInfo, int frameRefMapSize, int regRefMapSize) {
        if (debugInfo != null) {
            // copy the stack map
            int frameRefMapBytes;
            if (debugInfo.hasStackRefMap()) {
                frameRefMapBytes = debugInfo.frameRefMap.copyTo(data, refmapIndex, -1);
                assert new CiBitMap(data, refmapIndex, frameRefMapSize).equals(debugInfo.frameRefMap);
            } else {
                frameRefMapBytes = 0;
            }
            // copy the register map
            if (debugInfo.hasRegisterRefMap()) {
                debugInfo.registerRefMap.copyTo(data, refmapIndex + frameRefMapBytes, regRefMapSize);
                assert new CiBitMap(data, refmapIndex + frameRefMapBytes, regRefMapSize).equals(debugInfo.registerRefMap);
            }
        }
    }

    /**
     * Gets the index in {@code #data} at which the register reference map for stop {@code index} starts.
     */
    public int regRefMapStart(C1XTargetMethod tm, int index) {
        return index * tm.totalRefMapSize() + tm.frameRefMapSize();
    }

    /**
     * Gets the index in {@code #data} at which the frame reference map for stop {@code index} starts.
     */
    public int frameRefMapStart(C1XTargetMethod tm, int index) {
        return index * tm.totalRefMapSize();
    }

    /**
     * Gets the frame reference map for a given stop index.
     */
    public CiBitMap frameRefMapAt(C1XTargetMethod tm, int index) {
        return new CiBitMap(data, index * tm.totalRefMapSize(), tm.frameRefMapSize());
    }

    /**
     * Gets the register reference map for a given stop index.
     */
    public CiBitMap regRefMapAt(C1XTargetMethod tm, int index) {
        return new CiBitMap(data, index * tm.totalRefMapSize() + tm.frameRefMapSize(), regRefMapSize());
    }

    /**
     * Iterates over the code positions encoded for a given stop index.
     *
     * @param tm the target method associated with this debug info
     * @param cpc a closure called for each bytecode location in the inlining chain rooted
     *        at {@code index} (inner most callee first)
     * @param index the index of a frame
     * @return the number of code positions iterated over (i.e. the number of times
     *         {@link CodePosClosure#doCodePos(ClassMethodActor, int)} was called
     */
    public int forEachCodePos(C1XTargetMethod tm, CodePosClosure cpc, int index) {
        int count = 0;
        final DecodingStream in = new DecodingStream(data);
        int fpt = (tm.totalRefMapSize()) * tm.stopPositions().length;
        int frameIndex = index;

        while (true) {
            count++;
            in.pos = framePos(fpt, frameIndex);
            int encCallerIndex = in.decodeUInt();
            int holderID = in.decodeUInt();
            ClassActor holder = ClassID.toClassActor(holderID);
            int m = in.decodeUInt();
            MethodActor method;
            int bci;
            if ((m & 1) == 1) {
                bci = -1;
                int memberIndex = m >>> 1;
                method = holder.getLocalMethodActor(memberIndex);
            } else {
                int memberIndex = m >>> 1;
                method = holder.getLocalMethodActor(memberIndex);
                bci = in.decodeUInt();
            }
            if (!cpc.doCodePos((ClassMethodActor) method, bci)) {
                return count;
            }
            if (encCallerIndex == NO_FRAME) {
                return count;
            }
            int callerIndex = encCallerIndex - FIRST_FRAME;
            assert frameIndex != callerIndex;
            frameIndex = callerIndex;
        }
    }

    /**
     * Decodes the frame at a given stop index.
     *
     * @param tm the target method associated with this debug info
     * @param index the index of a frame
     * @return the frame at {@code index}
     */
    public CiFrame frameAt(C1XTargetMethod tm, int index) {
        final DecodingStream in = new DecodingStream(data);
        int fpt = (tm.totalRefMapSize()) * tm.stopPositions().length;
        return decodeFrame(in, fpt, index);
    }

    /**
     * Gets the position at which a given frame is encoded in {@link #data}.
     *
     * @param fpt the position of the FPT in {@link #data}
     * @param index the index of an entry in the FPT
     * @return the value of entry {@code index} in the FPT
     */
    int framePos(int fpt, int index) {
        if ((data.length & 0xFFFF) == data.length) {
            int pos = fpt + (index * 2);
            // entry is an unsigned short
            int hi = data[pos] & 0xff;
            int lo = data[pos + 1] & 0xff;
            return (hi << 8) | (lo << 0);
        } else {
            // entry is an int
            int pos = fpt + (index * 4);
            int b0 = data[pos] & 0xff;
            int b1 = data[pos + 1] & 0xff;
            int b2 = data[pos + 2] & 0xff;
            int b3 = data[pos + 3] & 0xff;
            return (b0 << 24) | (b1 << 16) | (b2 << 8) | (b3 << 0);
        }
    }

    /**
     * Decodes a frame denoted by a given frame index.
     *
     * @param fpt the position of the FPT in {@link #data}
     * @param frameIndex the index of an entry in the FPT
     * @return the decoded frame
     */
    CiFrame decodeFrame(DecodingStream in, int fpt, int frameIndex) {
        int framePos = framePos(fpt, frameIndex);
        if (framePos == 0) {
            return null;
        }
        in.pos = framePos;
        int encCallerIndex = in.decodeUInt();
        int holderID = in.decodeUInt();
        ClassActor holder = ClassID.toClassActor(holderID);
        int m = in.decodeUInt();
        RiMethod method;
        int bci;
        if ((m & 1) == 1) {
            bci = -1;
            int memberIndex = m >>> 1;
            method = holder.getLocalMethodActor(memberIndex);
            assert method != null;
        } else {
            int memberIndex = m >>> 1;
            method = holder.getLocalMethodActor(memberIndex);
            assert method != null;
            bci = in.decodeUInt();
        }
        int numLocals = in.decodeUInt();
        int numStack = in.decodeUInt();
        int numLocks = in.decodeUInt();

        int n = numLocals + numStack + numLocks;
        CiValue[] values = new CiValue[n];
        for (int i = 0; i < n; i++) {
            values[i] = readValue(in);
        }

        CiFrame caller = null;
        if (encCallerIndex != NO_FRAME) {
            int callerIndex = encCallerIndex - FIRST_FRAME;
            assert frameIndex != callerIndex;
            caller = decodeFrame(in, fpt, callerIndex);
        }
        return new CiFrame(caller, method, bci, values, numLocals, numStack, numLocks);
    }

    static int totalDebugInfos;
    static long totalDebugInfoBytes;
    static long totalCode;

    public static void dumpStats(PrintStream out) {
        out.println("-- DebugInfo stats --");
        out.println("  " + totalDebugInfos + " DebugInfo objects");
        out.println("  " + totalDebugInfoBytes + " DebugInfo bytes (avg per target method: " + (float) ((double) totalDebugInfoBytes / totalDebugInfos) + ")");
        out.println("  " + totalCode + " code bytes");
        out.println("  " + objectConstants.size() + " object constants");
        out.println("  " + nonObjectConstants.size() + " non-object constants");
        out.println("  " + valuesEncoded + " values encoded (avg bytes per value: "  + (float) ((double) valuesEncodedSize / valuesEncoded) + ")");
    }
}