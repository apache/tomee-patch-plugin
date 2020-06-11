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
import org.objectweb.asm.util.ASMifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.tomitribe.jkta.usage.Is;
import org.tomitribe.util.Hex;
import org.tomitribe.util.IO;
import org.tomitribe.util.PrintString;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * @version $Revision$ $Date$
 */
public class Asmifier {

    private static final AtomicReference<File> tmpDir = new AtomicReference<File>(null);

    static {
        try {
            final File asmifier = File.createTempFile("Asmifier", null);
            tmpDir.set(asmifier.getParentFile());
            if (!asmifier.delete()) {
                asmifier.deleteOnExit();
            }
        } catch (final IOException e) {
            throw new RuntimeException("Asmifier failed to locate a temporary directory");
        }
    }

    public static void hex(final ClassLoader classLoader, final String className) throws IOException {
        final String internalName = className.replace('.', '/') + ".class";
        final URL resource = classLoader.getResource(internalName);

        if (null == resource) {
            throw new IOException("Failed to find resource: " + internalName);
        }
        System.out.println(className);
        final InputStream inputStream = resource.openStream();
        byte[] bytes = IO.readBytes(inputStream);
        for (final byte b : bytes) {
            System.out.printf("%-2s %-2s%n", Hex.toString(new byte[]{b}), (char) b);
        }
    }

    public static void print(final ClassLoader classLoader, final String className) throws IOException {
        final String internalName = className.replace('.', '/') + ".class";
        final URL resource = classLoader.getResource(internalName);

        if (null == resource) {
            throw new IOException("Failed to find resource: " + internalName);
        }

        final ClassReader reader = new ClassReader(resource.openStream());


        final File file = new File("/tmp/" + className);

        write(reader, file);
    }

    private static void write(final ClassReader reader, final File file) throws IOException {
        final OutputStream write = IO.write(file);
        write(reader, write);
    }

    public static void write(final ClassReader reader, final OutputStream write) throws IOException {
        final TraceClassVisitor visitor = new TraceClassVisitor(null, new ASMifier(), new PrintWriter(write));
        reader.accept(visitor, ClassReader.SKIP_DEBUG);
        write.close();
    }

    public static void asmify(final Class clazz, final String suffix) throws IOException {
        asmify(clazz.getName(), Bytecode.readClassFile(clazz.getClassLoader(), clazz), suffix);
    }

    public static void asmify(final ClassLoader loader, final String className, final String suffix) throws IOException {
        asmify(className, Bytecode.readClassFile(loader, className), suffix);
    }

    public static void asmify(final String className, final byte[] bytes, final String suffix) throws IOException {
        final ClassReader reader = new ClassReader(bytes);
        final File file = new File(tmpDir.get(), className + "." + suffix);

        write(reader, file);
    }

    public static String asmify(final byte[] actualBytes) throws IOException {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        write(new ClassReader(actualBytes), byteArrayOutputStream);
        return new String(byteArrayOutputStream.toByteArray());
    }

    public static String asmifyJar(final File jar) throws IOException {
        final PrintString printString = new PrintString();
        final InputStream read = IO.read(jar);
        asmifyJar(read, printString);
        return printString.toString();
    }

    private static void asmifyJar(final InputStream inputStream, final PrintStream out) throws IOException {
        final ZipInputStream zipInputStream = new ZipInputStream(inputStream);

        ZipEntry entry;
        while ((entry = zipInputStream.getNextEntry()) != null) {

            entryMetadata(entry, out);

            final String path = entry.getName();

            if (path.endsWith(".class")) {
                asmifyClass(zipInputStream, out);
            } else if (isZip(path)) {
                asmifyJar(zipInputStream, out);
            } else {
                IO.copy(zipInputStream, out);
            }
        }
    }

    private static void entryMetadata(final ZipEntry entry, final PrintStream out) {
        out.printf("%n-----------------------------------------------%n");
        out.printf("name: %s%n", entry.getName());
        out.printf("----%n");
    }

    private static void asmifyClass(final InputStream inputStream, final PrintStream out) {
        try {
            final byte[] bytes = IO.readBytes(inputStream);
            final String asmify = Asmifier.asmify(bytes);
            out.println(asmify);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isZip(final String path) {
        return Is.Zip.accept(path);
    }
}
