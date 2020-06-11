/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomee.patch.core;


import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.tomitribe.util.IO;

import java.io.IOException;
import java.net.URL;

/**
 * @version $Revision$ $Date$
 */
public class Bytecode {

    private Bytecode() {
        // no-op
    }

    public static byte[] readClassFile(final Class clazz) throws IOException {
        ClassLoader classLoader = clazz.getClassLoader();
        if (classLoader == null) {
            classLoader = Thread.currentThread().getContextClassLoader();
        }
        return readClassFile(classLoader, clazz);
    }

    public static byte[] readClassFile(final ClassLoader classLoader, final Class clazz) throws IOException {
        final String internalName = clazz.getName().replace('.', '/') + ".class";
        final URL resource = classLoader.getResource(internalName);
        return IO.readBytes(resource);
    }

    public static byte[] readClassFile(final ClassLoader classLoader, final String className) throws IOException {
        final String internalName = className.replace('.', '/') + ".class";
        final URL resource = classLoader.getResource(internalName);
        return IO.readBytes(resource);
    }

    public static void read(final byte[] originalBytes, final ClassVisitor classAdapter) {
        if (originalBytes == null) throw new IllegalStateException("bytecode array is null");
        final ClassReader cr = new ClassReader(originalBytes);
        cr.accept(classAdapter, ClassReader.EXPAND_FRAMES);
    }

}
