/*
 * Copyright (c) 2008, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.max.tele.memory;

import java.io.*;
import java.text.*;
import java.util.*;

import com.sun.max.lang.*;
import com.sun.max.tele.*;
import com.sun.max.tele.data.*;
import com.sun.max.tele.debug.*;
import com.sun.max.tele.method.*;
import com.sun.max.tele.object.*;
import com.sun.max.tele.object.TeleObjectFactory.ClassCount;
import com.sun.max.tele.reference.*;
import com.sun.max.tele.type.*;
import com.sun.max.tele.util.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.debug.*;
import com.sun.max.vm.heap.*;
import com.sun.max.vm.layout.*;
import com.sun.max.vm.reference.*;
import com.sun.max.vm.tele.*;

/**
 * Singleton cache of information about objects in the VM.
 * <p>
 * Initialization between this class and {@link VmClassAccess} are mutually
 * dependent.  The cycle is broken by creating this class in a partially initialized
 * state that only considers the boot heap region; this class is only made fully functional
 * with a call to {@link #initialize()}, which requires that {@link VmClassAccess} be
 * fully initialized.
 * <p>
 * Interesting heap state includes the list of memory regions allocated.
 * <p>
 * This class also provides access to a special root table in the VM, active
 * only when being inspected.  The root table allows inspection references
 * to track object locations when they are relocated by GC.
 * <p>
 * This class needs to be specialized by a helper class that
 * implements the interface {@link TeleHeapScheme}, typically
 * a class that contains knowledge of the heap implementation
 * configured into the VM.
 *
 * @see InspectableHeapInfo
 * @see TeleRoots
 * @see HeapScheme
 * @see TeleHeapScheme
 */
public final class VmObjectAccess extends AbstractVmHolder implements TeleVMCache, MaxObjects {

    private static final int STATS_NUM_TYPE_COUNTS = 10;

    /**
     * Name of system property that specifies the address where the heap is located, or where it should be relocated, depending
     * on the user of the class.
     */
    public static final String HEAP_ADDRESS_PROPERTY = "max.heap";

    private static final int TRACE_VALUE = 1;

    private static VmObjectAccess vmObjectAccess;

    public static VmObjectAccess make(TeleVM vm) {
        if (vmObjectAccess == null) {
            vmObjectAccess = new VmObjectAccess(vm);
        }
        return vmObjectAccess;
    }

    private final TimedTrace updateTracer;

    private long lastUpdateEpoch = -1L;

    private final String entityName = "Object Manager";

    private final String entityDescription;

    private List<MaxCodeLocation> inspectableMethods = null;

    private final TeleObjectFactory teleObjectFactory;

    private final Object statsPrinter = new Object() {
        @Override
        public String toString() {
            final StringBuilder msg = new StringBuilder();

            return msg.toString();
        }
    };

    private VmObjectAccess(TeleVM vm) {
        super(vm);
        final TimedTrace tracer = new TimedTrace(TRACE_VALUE, tracePrefix() + " creating");
        tracer.begin();

        this.teleObjectFactory = TeleObjectFactory.make(vm, vm.teleProcess().epoch());
        this.entityDescription = "Object creation and management for the " + vm().entityName();
        this.updateTracer = new TimedTrace(TRACE_VALUE, tracePrefix() + " updating");

        tracer.end(statsPrinter);
    }

    /** {@inheritDoc}
     * <p>
     * Updates the representation of every <strong>remote object</strong> surrogate
     * (represented as instances of subclasses of {@link TeleObject},
     * in case any of the information in the VM object has changed since the previous update.  This should not be
     * attempted until all information about allocated regions that might contain objects has been updated.
     */
    public void updateCache(long epoch) {
        teleObjectFactory.updateCache(epoch);
    }

    public String entityName() {
        return entityName;
    }

    public String entityDescription() {
        return entityDescription;
    }

    public MaxEntityMemoryRegion<MaxObjects> memoryRegion() {
        // The heap has no VM memory allocated, other than the regions allocated directly from the OS.
        return null;
    }

    public boolean contains(Address address) {
        // There's no real notion of containing an address here.
        return false;
    }

    public TeleObject representation() {
        // No distinguished object in VM runtime represents the heap.
        return null;
    }

    private static  final int MAX_VM_LOCK_TRIALS = 100;

    // TODO (mlvdv) this may eventually go away, in favor of isObjectOrigin and much  more precise management
    /**
     * Determines whether a location in VM memory is the origin of a VM object.
     *
     * @param address an absolute memory location in the VM.
     * @return whether there is an object whose origin is at the address, false if unable
     * to complete the check, for example if the VM is busy or terminated
     */
    public boolean isValidOrigin(Address address) {
        if (address.isZero()) {
            return false;
        }
        // TODO (mlvdv) Transition to the new reference management framework; use it for the regions supported so far
        final VmHeapRegion bootHeapRegion = vm().heap().bootHeapRegion();
        if (bootHeapRegion.contains(address)) {
            return bootHeapRegion.objectReferenceManager().isObjectOrigin(address);
        }

        final VmHeapRegion immortalHeapRegion = vm().heap().immortalHeapRegion();
        if (immortalHeapRegion != null && immortalHeapRegion.contains(address)) {
            return immortalHeapRegion.objectReferenceManager().isObjectOrigin(address);
        }

        final VmCodeCacheRegion compiledCodeRegion = vm().codeCache().findCompiledCodeRegion(address);
        if (compiledCodeRegion != null) {
            return compiledCodeRegion.objectReferenceManager().isObjectOrigin(address);
        }
        // For everything else use the old machinery
        try {
            if (!heap().contains(address) && (codeCache() == null || !codeCache().contains(address))) {
                return false;
            }
            if (false && heap().isInGC() && heap().containsInDynamicHeap(address)) {
                //  Assume that any reference to the dynamic heap is invalid during GC.
                return false;
            }
            if (false && vm().bootImage().vmConfiguration.debugging()) {
                final Pointer cell = Layout.originToCell(address.asPointer());
                // Checking is easy in a debugging build; there's a special word preceding each object
                final Word tag = memory().access().getWord(cell, 0, -1);
                return DebugHeap.isValidCellTag(tag);
            }
            // Now check using heuristic to see if there's actually an object stored at the location.
            return isObjectOriginHeuristic(address);
        } catch (TerminatedProcessIOException terminatedProcessIOException) {
        } catch (DataIOError dataAccessError) {
        } catch (IndexOutOfBoundsException indexOutOfBoundsException) {
        }
        return false;
    }

    /**
     * Determines heuristically whether a location in VM memory is the origin of a VM object, independent
     * of what region may contain it. May produce rare false positives.
     *
     * @param origin an absolute memory location in the VM.
     * @return whether there is an object whose origin is at the address, false if unable
     * to complete the check, for example if the VM is busy or terminated
     */
    public boolean isObjectOriginHeuristic(Address origin) {
        if (origin.isZero()) {
            return false;
        }
        try {
            // Check using none of the higher level services in the Inspector,
            // since this predicate is necessary to build those services.
            //
            // This check can produce a false positive, in particular when looking at a field (not in an
            // object header) that holds a reference to a dynamic hub.
            //
            // Keep following hub pointers until the same hub is traversed twice or
            // an address outside of heap or code region(s) is encountered.
            //
            // For all objects other than a {@link StaticTuple}, the maximum chain takes only two hops
            // find the distinguished object with self-referential hub pointer:  the {@link DynamicHub} for
            // class {@link DynamicHub}.
            //
            //  Typical pattern:    tuple --> dynamicHub of the tuple's class --> dynamicHub of the DynamicHub class
            Word hubWord = Layout.readHubReferenceAsWord(referenceManager().makeTemporaryRemoteReference(origin));
            for (int i = 0; i < 3; i++) {
                final RemoteTeleReference hubRef = referenceManager().makeTemporaryRemoteReference(hubWord.asAddress());
                final Pointer hubOrigin = hubRef.toOrigin();
                if (!heap().contains(hubOrigin)) {
                    return false;
                }
                final Word nextHubWord = Layout.readHubReferenceAsWord(hubRef);
                if (nextHubWord.equals(hubWord)) {
                    // We arrived at a DynamicHub for the class DynamicHub
                    if (i < 2) {
                        // All ordinary cases will have stopped by now
                        return true;
                    }
                    // This longer chain can only happen when we started with a {@link StaticTuple}.
                    // Perform a more precise test to check for this.
                    return isStaticTuple(origin);
                }
                hubWord = nextHubWord;
            }
        } catch (TerminatedProcessIOException terminatedProcessIOException) {
            return false;
        } catch (DataIOError dataAccessError) {
            return false;
        } catch (IndexOutOfBoundsException indexOutOfBoundsException) {
            return false;
        }
        return false;
    }


    public TeleObject findTeleObject(Reference reference) throws MaxVMBusyException {
        if (vm().tryLock(MAX_VM_LOCK_TRIALS)) {
            try {
                return makeTeleObject(reference);
            } finally {
                vm().unlock();
            }
        } else {
            throw new MaxVMBusyException();
        }
    }

    public TeleObject findObjectByOID(long id) {
        return teleObjectFactory.lookupObject(id);
    }

    public TeleObject findObjectAt(Address origin) {
        if (vm().tryLock(MAX_VM_LOCK_TRIALS)) {
            try {
                return makeTeleObject(vm().referenceManager().makeReference(origin.asPointer()));
            } catch (Throwable throwable) {
                // Can't resolve the address somehow
            } finally {
                vm().unlock();
            }
        }
        return null;
    }

    public TeleObject findObjectFollowing(Address cellAddress, long maxSearchExtent) {

        // Search limit expressed in words
        long wordSearchExtent = Long.MAX_VALUE;
        final int wordSize = vm().platform().nBytesInWord();
        if (maxSearchExtent > 0) {
            wordSearchExtent = maxSearchExtent / wordSize;
        }
        try {
            Pointer origin = cellAddress.asPointer();
            for (long count = 0; count < wordSearchExtent; count++) {
                origin = origin.plus(wordSize);
                if (isValidOrigin(origin)) {
                    return makeTeleObject(referenceManager().makeReference(origin));
                }
            }
        } catch (Throwable throwable) {
        }
        return null;
    }

    public TeleObject findObjectPreceding(Address cellAddress, long maxSearchExtent) {

        // Search limit expressed in words
        long wordSearchExtent = Long.MAX_VALUE;
        final int wordSize = vm().platform().nBytesInWord();
        if (maxSearchExtent > 0) {
            wordSearchExtent = maxSearchExtent / wordSize;
        }
        try {
            Pointer origin = cellAddress.asPointer();
            for (long count = 0; count < wordSearchExtent; count++) {
                origin = origin.minus(wordSize);
                if (isValidOrigin(origin)) {
                    return makeTeleObject(referenceManager().makeReference(origin));
                }
            }
        } catch (Throwable throwable) {
        }
        return null;
    }

    /**
     * Factory method for canonical {@link TeleObject} surrogate for heap objects in the VM. Specific subclasses are
     * created for Maxine implementation objects of special interest, and for other objects for which special treatment
     * is desired.
     * <p>
     * Returns null for the distinguished zero {@link Reference}.
     * <p>
     * Must be called with current thread holding the VM lock.
     * <p>
     * Care is taken to avoid I/O with the VM during synchronized access to the canonicalization map. There is a small
     * exception to this for {@link TeleTargetMethod}, which can lead to infinite regress if the constructors for
     * mutually referential objects (notably {@link TeleClassMethodActor}) also create {@link TeleObject}s.
     *
     * @param reference non-null location of a Java object in the VM
     * @return canonical local surrogate for the object
     */
    public TeleObject makeTeleObject(Reference reference) {
        return teleObjectFactory.make(reference);
    }

    /**
     * Finds an object in the VM that has been located at a particular place in memory, but which
     * may have been relocated.
     * <p>
     * Must be called in thread holding the VM lock
     *
     * @param origin an object origin in the VM
     * @return the object originally at the origin, possibly relocated
     */
    public TeleObject getForwardedObject(Pointer origin) {
        final Reference forwardedObjectReference = referenceManager().makeReference(heap().getForwardedOrigin(origin));
        return teleObjectFactory.make(forwardedObjectReference);
    }


    /**
     * Avoid potential circularity problems by handling heap queries specially when we
     * know we are in a refresh cycle during which information about heap regions may not
     * be well formed.  This variable is true during those periods.
     */
    private boolean updatingHeapMemoryRegions = false;

    /**
     * Low level predicate for identifying the special case of a {@link StaticTuple} in the VM,
     * using only the most primitive operations, since it is needed for building all the higher-level
     * services in the Inspector.
     * <p>
     * Note that this predicate is not precise; it may very rarely return a false positive.
     * <p>
     * The predicate depends on the following chain in the VM heap layout:
     * <ol>
     *  <li>The hub of a {@link StaticTuple} points at a {@link StaticHub}</li>
     *  <li>A field in a {@link StaticHub} points at the {@link ClassActor} for the class being implemented.</li>
     *  <li>A field in a {@link ClassActor} points at the {@link StaticTuple} for the class being implemented,
     *  which will point back at the original location if it is in fact a {@link StaticTuple}.</li>
     *  </ol>
     *  No type checks are performed, however, since this predicate must not depend on such higher-level information.
     *
     * @param origin a memory location in the VM
     * @return whether the object (probably)  points at an instance of {@link StaticTuple}
     * @see #isValidOrigin(Pointer)
     */
    private boolean isStaticTuple(Address origin) {
        // If this is a {@link StaticTuple} then a field in the header points at a {@link StaticHub}
        Word staticHubWord = Layout.readHubReferenceAsWord(referenceManager().makeTemporaryRemoteReference(origin));
        final RemoteTeleReference staticHubRef = referenceManager().makeTemporaryRemoteReference(staticHubWord.asAddress());
        final Pointer staticHubOrigin = staticHubRef.toOrigin();
        if (!heap().contains(staticHubOrigin) && !codeCache().contains(staticHubOrigin)) {
            return false;
        }
        // If we really have a {@link StaticHub}, then a known field points at a {@link ClassActor}.
        final int hubClassActorOffset = fields().Hub_classActor.fieldActor().offset();
        final Word classActorWord = memory().readWord(staticHubOrigin, hubClassActorOffset);
        final RemoteTeleReference classActorRef = referenceManager().makeTemporaryRemoteReference(classActorWord.asAddress());
        final Pointer classActorOrigin = classActorRef.toOrigin();
        if (!heap().contains(classActorOrigin) && !codeCache().contains(classActorOrigin)) {
            return false;
        }
        // If we really have a {@link ClassActor}, then a known field points at the {@link StaticTuple} for the class.
        final int classActorStaticTupleOffset = fields().ClassActor_staticTuple.fieldActor().offset();
        final Word staticTupleWord = memory().readWord(classActorOrigin, classActorStaticTupleOffset);
        final RemoteTeleReference staticTupleRef = referenceManager().makeTemporaryRemoteReference(staticTupleWord.asAddress());
        final Pointer staticTupleOrigin = staticTupleRef.toOrigin();
        // If we really started with a {@link StaticTuple}, then this field will point at it
        return staticTupleOrigin.equals(origin);
    }

    public int gcForwardingPointerOffset() {
        // TODO (mlvdv) Should only be called if in a region being managed by relocating GC
        return heap().gcForwardingPointerOffset();
    }

    public  boolean isObjectForwarded(Pointer origin) {
        // TODO (mlvdv) Should only be called if in a region being managed by relocating GC
        return heap().isObjectForwarded(origin);
    }

    public void printSessionStats(PrintStream printStream, int indent, boolean verbose) {
        final String indentation = Strings.times(' ', indent);
        final NumberFormat formatter = NumberFormat.getInstance();
        printStream.print(indentation + "Inspection references: " + formatter.format(teleObjectFactory.referenceCount()) +
                        " (" + formatter.format(teleObjectFactory.liveObjectCount()) + " live)\n");
        final TreeSet<ClassCount> sortedObjectsCreatedPerType = new TreeSet<ClassCount>(new Comparator<ClassCount>() {
            @Override
            public int compare(ClassCount o1, ClassCount o2) {
                return o2.value - o1.value;
            }
        });
        sortedObjectsCreatedPerType.addAll(teleObjectFactory.objectsCreatedPerType());
        printStream.println(indentation + "TeleObjects created: " + formatter.format(teleObjectFactory.objectsCreatedCount()));
        printStream.println(indentation + "TeleObjects created (top " + STATS_NUM_TYPE_COUNTS + " types)");
        int countsPrinted = 0;
        for (ClassCount count : sortedObjectsCreatedPerType) {
            if (countsPrinted++ >= STATS_NUM_TYPE_COUNTS) {
                break;
            }
            if (verbose) {
                printStream.println("    " + count.value + "\t" + count.type.getName());
            } else {
                printStream.println("    " + count.value + "\t" + count.type.getSimpleName());
            }
        }
    }

}
