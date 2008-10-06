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
/*VCSID=8a2ae29a-d59c-4545-a2c7-750501b3246b*/
package com.sun.max.vm;

import com.sun.max.*;
import com.sun.max.vm.type.*;

/**
 * @see MaxPackage
 *
 * @author Bernd Mathiske
 */
public class Package extends VMPackage {

    public Package() {
        super();
    }

    private static final String PACKAGE_NAME = new Package().name();

    /**
     * Determines if a given class is part of the MaxineVM code base.
     */
    public static boolean contains(Class javaClass) {
        return javaClass.getName().startsWith(PACKAGE_NAME);
    }

    /**
     * Determines if a given class represented by a type descriptor is part of the MaxineVM code base.
     */
    public static boolean contains(TypeDescriptor typeDescriptor) {
        return typeDescriptor.toJavaString().startsWith(PACKAGE_NAME);
    }
}
