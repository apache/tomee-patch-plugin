/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomee.patch.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.tomitribe.util.IO;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class Transformation {

    private Transformation() {
    }

    public static File transform(final File jar) throws IOException {
        final File tempFile = File.createTempFile(jar.getName(), ".transformed");

        try (final InputStream inputStream = IO.read(jar)) {
            try (final OutputStream outputStream = IO.write(tempFile)) {
                scanJar(inputStream, outputStream);
                return tempFile;
            }
        }
    }

    private static void scanJar(final InputStream inputStream, final OutputStream outputStream) throws IOException {
        final ZipInputStream zipInputStream = new ZipInputStream(inputStream);
        final ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream);

        ZipEntry oldEntry;
        while ((oldEntry = zipInputStream.getNextEntry()) != null) {
            // TODO: the name may be changed in transformation
            final String path = oldEntry.getName();
            final ZipEntry newEntry = new ZipEntry(path);

            copyAttributes(oldEntry, newEntry);

            zipOutputStream.putNextEntry(newEntry);

            try {
                if (path.endsWith(".class")) {
                    scanClass(zipInputStream, zipOutputStream);
                } else if (isZip(path)) {
                    scanJar(zipInputStream, zipOutputStream);
                } else {
                    IO.copy(zipInputStream, zipOutputStream);
                }
            } finally {
                zipOutputStream.closeEntry();
            }
        }
        zipOutputStream.close();
    }

    private static void copyAttributes(final ZipEntry oldEntry, final ZipEntry newEntry) {
        Copy.copy(oldEntry, newEntry)
                .att(ZipEntry::getTime, ZipEntry::setTime)
                .att(ZipEntry::getComment, ZipEntry::setComment)
                .att(ZipEntry::getExtra, ZipEntry::setExtra)
                .att(ZipEntry::getMethod, ZipEntry::setMethod)
                .att(ZipEntry::getCreationTime, ZipEntry::setCreationTime)
                .att(ZipEntry::getLastModifiedTime, ZipEntry::setLastModifiedTime)
                .att(ZipEntry::getLastAccessTime, ZipEntry::setLastAccessTime);
    }

    private static boolean isZip(final String path) {
        return Is.Zip.accept(path);
    }

    private static void scanClass(final InputStream in, final OutputStream outputStream) throws IOException {
        final ClassWriter classWriter = new ClassWriter(Opcodes.ASM8);
        final ClassTransformer classTransformer = new ClassTransformer(classWriter);
        final ClassReader classReader = new ClassReader(in);
        classReader.accept(classTransformer, 0);
        final byte[] bytes = classWriter.toByteArray();
        outputStream.write(bytes);
    }

}
