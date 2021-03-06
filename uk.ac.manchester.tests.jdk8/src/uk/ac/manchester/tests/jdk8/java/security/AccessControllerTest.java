/*
 * Copyright (c) 2017, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
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
 */
package uk.ac.manchester.tests.jdk8.java.security;

import static org.junit.Assert.assertEquals;

import java.security.*;

import org.junit.Test;

public class AccessControllerTest {

    @Test
    public void invokeLambdaThroughDoPrivileged() {
        String res = null;
        try {
            res = AccessController.doPrivileged((PrivilegedExceptionAction<String>) () -> System.getProperty("java.vendor"));
        } catch (PrivilegedActionException ex) {
            ex.printStackTrace();
        }
        assertEquals(res, System.getProperty("java.vendor"));
    }

}
