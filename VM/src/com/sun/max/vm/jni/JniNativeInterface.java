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
/*VCSID=d57f32ac-5963-4182-98c4-20a27364ec38*/
package com.sun.max.vm.jni;

import java.io.*;
import java.util.regex.*;

import com.sun.max.annotate.*;
import com.sun.max.collect.*;
import com.sun.max.lang.*;
import com.sun.max.memory.*;
import com.sun.max.program.*;
import com.sun.max.unsafe.*;
import com.sun.max.util.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.CompilationScheme.*;

/**
 * This class provides static functions for accessing the C data structure holding
 * the pointers to all the JNI functions. This is the data structure labeled as "Array of
 * pointers to JNI functions" in <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jni/spec/design.html#wp16696">Figure 2-1</a>.
 *
 * @author Bernd Mathiske
 * @author Doug Simon
 */
public final class JniNativeInterface {

    private JniNativeInterface() {
    }

    @PROTOTYPE_ONLY
    private static StaticMethodActor[] getJniFunctionActors() {
        final StaticMethodActor[] jniFunctionActors = Arrays.filter(ClassActor.fromJava(JniFunctions.class).localStaticMethodActors(), new Predicate<StaticMethodActor>() {
            public boolean evaluate(StaticMethodActor staticMethodActor) {
                return staticMethodActor.isJniFunction();
            }
        });

        checkAgainstJniHeaderFile(jniFunctionActors);

        return jniFunctionActors;
    }

    @PROTOTYPE_ONLY
    private static void checkAgainstJniHeaderFile(StaticMethodActor[] jniFunctionActors) {
        String jniHeaderFilePath = System.getProperty("max.jni.headerFile");
        if (jniHeaderFilePath == null) {
            jniHeaderFilePath = System.getProperty("java.home");
            final String jreTail = File.separator + "jre";
            if (jniHeaderFilePath.endsWith(jreTail)) {
                jniHeaderFilePath = Strings.chopSuffix(jniHeaderFilePath, jreTail);
            }
            jniHeaderFilePath += File.separator + "include" + File.separator + "jni.h";
        }

        final File jniHeaderFile = new File(jniHeaderFilePath);
        ProgramError.check(jniHeaderFile.exists(), "JNI header file " + jniHeaderFile + " does not exist");

        // This program expects all JNI function prototypes in jni.h to split over 2 lines,
        // with the name of the function being on the first line and having the form:
        //
        //    <return_type> (JNICALL *<function name>)
        //
        // For example:
        //
        //    jlong (JNICALL *CallLongMethod)
        //
        final Pattern pattern = Pattern.compile(".*\\(JNICALL \\*([^\\)]+)\\).*");

        final AppendableIndexedSequence<String> jniFunctionNames = new ArrayListSequence<String>();

        // Prepend the reserved function slots
        jniFunctionNames.append("reserved0");
        jniFunctionNames.append("reserved1");
        jniFunctionNames.append("reserved2");
        jniFunctionNames.append("reserved3");

        try {
            final BufferedReader lineReader = new BufferedReader(new FileReader(jniHeaderFile));
            String line;
            while ((line = lineReader.readLine()) != null) {
                final Matcher matcher = pattern.matcher(line);
                if (matcher.matches() && !line.contains("JavaVM *vm")) {
                    final String functionName = matcher.group(1);
                    jniFunctionNames.append(functionName);
                }
            }
        } catch (IOException ioException) {
            ProgramError.unexpected("Error reading JNI header file " + jniHeaderFilePath, ioException);
        }

        // Add the two MaxineVM specific JNI functions
        jniFunctionNames.append("GetNumberOfArguments");
        jniFunctionNames.append("GetKindsOfArguments");

        for (int i = 0; i != jniFunctionActors.length; ++i) {
            final String jniFunctionName = jniFunctionNames.get(i);
            final String jniFunctionActorName = jniFunctionActors[i].name().toString();
            ProgramError.check(jniFunctionName.equals(jniFunctionActorName), "JNI function " + jniFunctionName + " at index " + i + " does not match JNI function actor " + jniFunctionActorName);
        }

        ProgramError.check(jniFunctionNames.length() == jniFunctionActors.length);
    }

    private static final StaticMethodActor[] _jniFunctionActors;
    static {
        if (MaxineVM.isPrototyping()) {
            _jniFunctionActors = getJniFunctionActors();
        } else {
            throw ProgramError.unexpected();
        }
    }

    public static StaticMethodActor[] jniFunctionActors() {
        return _jniFunctionActors;
    }

    private static Pointer _pointer = Pointer.zero();

    public static Pointer pointer() {
        if (_pointer.isZero()) {
            _pointer = Memory.mustAllocate(_jniFunctionActors.length * Word.size());
        }
        return _pointer;
    }

    /**
     * Patches the {@link #_pointer} array for certain JNI functions that are implemented in C for
     * portability reasons (i.e. handling of varargs).
     * 
     * {@link #_pointer} is implicitly passed down to the implementing C function by means of its 'JNIEnv' parameter.
     */
    @C_FUNCTION
    private static native void nativeInitializeJniInterface(Pointer jniEnvironment);

    /**
     * Initializes the JNI function vector, aka struct JniNativeInterface in C/C++ land.
     * Must be called at VM startup, relying on having as few other features working as possible at that moment.
     */
    public static void initialize() {
        for (int i = 0; i < _jniFunctionActors.length; i++) {
            final Word functionPointer = Static.getCriticalEntryPoint(_jniFunctionActors[i], CallEntryPoint.C_ENTRY_POINT);
            pointer().setWord(i, functionPointer);
        }
        nativeInitializeJniInterface(pointer());
    }

}
