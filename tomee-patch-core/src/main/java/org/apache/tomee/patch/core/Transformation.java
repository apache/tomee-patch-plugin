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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class Transformation {

    private final List<Clazz> classes = new ArrayList<Clazz>();
    private final Log log;

    public Transformation() {
        this.log = new NullLog();
    }


    public Transformation(final List<Clazz> classes, final Log log) {
        this.classes.addAll(classes);
        this.log = log;
    }

    public static File transform(final File jar) throws IOException {
        return new Transformation().transformArchive(jar);
    }

    public File transformArchive(final File jar) throws IOException {
        final File tempFile = File.createTempFile(jar.getName(), ".transformed");

        try (final InputStream inputStream = IO.read(jar)) {
            try (final OutputStream outputStream = IO.write(tempFile)) {
                final Jar old = Jar.enter(jar.getName());
                try {
                    scanJar(inputStream, outputStream);
                } finally {
                    Jar.exit(old);
                }
                return tempFile;
            }
        }
    }

    private void scanJar(final InputStream inputStream, final OutputStream outputStream) throws IOException {
        final ZipInputStream zipInputStream = new ZipInputStream(inputStream);
        final ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream);

        ZipEntry oldEntry;
        while ((oldEntry = zipInputStream.getNextEntry()) != null) {
            // TODO: the name may be changed in transformation
            final String path = oldEntry.getName();

            /*
             * If this entry has been patched, skip it
             * We will add the patched version at the end
             */
            if (isPatched(path)) {
                log.debug("Skipping class " + path);
                IO.copy(zipInputStream, skipped);
                continue;
            }

            final ZipEntry newEntry = new ZipEntry(path);

//            copyAttributes(oldEntry, newEntry);

            zipOutputStream.putNextEntry(newEntry);

            try {
                if (path.endsWith(".class")) {
                    scanClass(zipInputStream, zipOutputStream);
                } else if (isZip(path)) {
                    final Jar old = Jar.enter(path);
                    try {
                        scanJar(zipInputStream, zipOutputStream);
                    } finally {
                        Jar.exit(old); // restore the old state
                    }
                } else {
                    IO.copy(zipInputStream, zipOutputStream);
                }
            } finally {
                zipOutputStream.closeEntry();
            }
        }

        // If we skipped any classes, add them now
        if (Jar.current().hasPatches()) {
            log.info("Patching " + Jar.current().getName());
            for (final Clazz clazz : Jar.current().getSkipped()) {
                log.info("Applying patch " + clazz.getName());

                final ZipEntry newEntry = new ZipEntry(clazz.getName());
                zipOutputStream.putNextEntry(newEntry);

                // Run any transformations on these classes as well
                scanClass(IO.read(clazz.getFile()), zipOutputStream);

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

    public static class Jar {
        private static final AtomicReference<Jar> current = new AtomicReference<>(new Jar("<none>"));

        private final Set<Clazz> patches = new HashSet<>();
        private final String name;

        public Jar(final String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public boolean hasPatches() {
            return patches.size() > 0;
        }

        public static Jar current() {
            return current.get();
        }

        public static Jar enter(final String name) {
            return current.getAndSet(new Jar(name));
        }

        public static void exit(final Jar oldJar) {
            current.getAndSet(oldJar);
        }

        public Collection<Clazz> getSkipped() {
            return patches;
        }

        /**
         * Select all classes that are a patch for the specified class.
         * This will also add any applicable inner-classes of the specified class
         */
        public void patch(final Clazz clazz, final List<Clazz> potentialPatches) {
            potentialPatches.stream()
                    .filter(potentialPatch -> potentialPatch.getName().startsWith(clazz.getPrefix()))
                    .forEach(patches::add);
        }
    }

    private boolean isPatched(final String path) {
        for (final Clazz clazz : classes) {
            if (path.startsWith(clazz.getPrefix())) {
                Jar.current().patch(clazz, classes);
                return true;
            }
        }
        return false;
    }

    private static final OutputStream skipped = new OutputStream() {
        @Override
        public void write(final int b) {
        }
    };
}
