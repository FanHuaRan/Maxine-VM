/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
/*VCSID=338d85ac-337b-456d-8eaf-25b4bcdd151e*/
package com.sun.max.vm.compiler.tir.pipeline;

import com.sun.max.vm.compiler.tir.*;

public class TirInstructionFilter extends TirInstructionAdapter implements TirMessageSink {
    protected final TirMessageSink _receiver;

    public TirInstructionFilter(TirMessageSink receiver) {
        _receiver = TirPipeline.connect(this, receiver);
    }

    @Override
    public void receive(TirMessage message) {
        if (message instanceof TirInstruction) {
            final TirInstruction instruction = (TirInstruction) message;
            if (filter(instruction) == false) {
                return;
            }
        }
        message.accept(this);
    }

    /**
     * Prevents instructions from being inspected by this filter.
     */
    protected boolean filter(TirInstruction instruction) {
        return true;
    }

    @Override
    public void visit(TirMessage message) {
        forward(message);
    }

    public void forward(TirMessage message) {
        _receiver.receive(message);
    }
}
