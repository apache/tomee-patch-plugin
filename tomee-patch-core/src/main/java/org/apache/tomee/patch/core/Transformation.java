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
import org.tomitribe.swizzle.stream.StreamBuilder;
import org.tomitribe.util.IO;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.tomitribe.jkta.util.Predicates.not;

public class Transformation {

    private final List<Clazz> classes = new ArrayList<Clazz>();
    private final Log log;
    private final Map<String, String> replacements;

    public Transformation() {
        this.log = new NullLog();
        this.replacements = Collections.EMPTY_MAP;
    }


    public Transformation(final List<Clazz> classes, final Map<String, String> replacements, final Log log) {
        this.classes.addAll(classes);
        this.log = log;
        this.replacements = replacements == null ? Collections.EMPTY_MAP : replacements;
    }

    public static File transform(final File jar) throws IOException {
        return new Transformation().transformArchive(jar);
    }

    public File transformArchive(final File jar) throws IOException {
        final File tempFile = File.createTempFile(jar.getName(), ".transformed");

        try (final InputStream inputStream = IO.read(jar)) {
            try (final OutputStream outputStream = IO.write(tempFile)) {
                scanJar(jar.getName(), inputStream, outputStream);
            }
        }

        return tempFile;
    }

    private void scanJar(final String name, final InputStream inputStream, final OutputStream outputStream) throws IOException {
        final Jar oldJar = Jar.enter(name);
        final Jar jar = Jar.current();
        try {
            final ZipInputStream zipInputStream = new ZipInputStream(inputStream);
            final ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream);

            ZipEntry oldEntry;
            while ((oldEntry = zipInputStream.getNextEntry()) != null) {
                // TODO: the name may be changed in transformation
                final String path = updatePath(oldEntry.getName());

                if (skip(path)) {
                    IO.copy(zipInputStream, skipped);
                    continue;
                }

                /*
                 * If this entry has been patched, skip it
                 * We will add the patched version at the end
                 */
                if (isPatched(path, jar)) {
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
                        scanJar(path, zipInputStream, zipOutputStream);
                    } else if (copyUnmodified(path)) {
                        IO.copy(zipInputStream, zipOutputStream);
                    } else {
                        scanResource(path, zipInputStream, zipOutputStream);
                    }
                } finally {
                    zipOutputStream.closeEntry();
                }
            }

            // If we skipped any classes, add them now
            if (jar.hasPatches()) {
                log.info("Patching " + jar.getName());
                for (final Clazz clazz : jar.getSkipped()) {
                    log.debug("Applying patch " + clazz.getName());

                    final ZipEntry newEntry = new ZipEntry(clazz.getName());
                    zipOutputStream.putNextEntry(newEntry);

                    // Run any transformations on these classes as well
                    IO.copy(IO.read(clazz.getFile()), zipOutputStream);

                    zipOutputStream.closeEntry();
                    clazz.applied();
                }
            }
            zipOutputStream.finish();
        } catch (IOException e) {
            throw new IOException(jar.getPath() + e.getMessage(), e);
        } finally {
            Jar.exit(oldJar);
        }
    }

    /**
     * Skip signed jar public key files.  We most definitely
     * have tampered with the jar.
     */
    private boolean skip(final String name) {
        if (name.startsWith("META-INF/")) {
            if (name.endsWith(".SF")) return true;
            if (name.endsWith(".DSA")) return true;
            if (name.endsWith(".RSA")) return true;
        }
        return false;
    }

    private String updatePath(final String name) {
        return name.replace("resources/javax.faces", "resources/jakarta.faces");
    }

    private boolean copyUnmodified(final String path) {
        if (path.endsWith("META-INF/DEPENDENCIES")) return true;
        if (path.endsWith("META-INF/dependencies.xml")) return true;
        if (path.endsWith("changelog.html")) return true;
        if (path.endsWith("RELEASE-NOTES.txt")) return true;
        if (path.endsWith("pom.xml")) return true;
        return false;
    }

    private void scanResource(final String path, InputStream inputStream, final OutputStream outputStream) throws IOException {
        if (path.endsWith("openwebbeans.properties")) {
            inputStream = StreamBuilder.create(inputStream)
                    .replace("org.apache.webbeans.proxy.mapping.javax.enterprise", "org.apache.webbeans.proxy.mapping.jakarta.enterprise")
                    .replace("\n        /javax, \\\n", "\n        /javax, \\\n        /jakarta, \\\n")
                    .replace("javax.enterprise.inject.allowProxying.classes", "jakarta.enterprise.inject.allowProxying.classes")
                    .get();
        }

        inputStream = StreamBuilder.create(inputStream)
                .replace("javax.jsp", "jakarta.servlet.jsp")
                .replace("serlvet", "servlet")
                .replace("javax.transaction.TransactionManager", "jakarta.transaction.TransactionManager")
                .replace("javax.transaction.Transaction", "jakarta.transaction.Transaction")
                .replace("javax.annotation.Resource", "jakarta.annotation.Resource")
                .replace("javax.activation", "jakarta.activation")
                .replace("javax.batch", "jakarta.batch")
                .replace("javax.decorator", "jakarta.decorator")
                .replace("javax.ejb", "jakarta.ejb")
                .replace("javax.el", "jakarta.el")
                .replace("javax.enterprise.concurrent", "jakarta.enterprise.concurrent")
                .replace("javax.faces", "jakarta.faces")
                .replace("javax.inject", "jakarta.inject")
                .replace("javax.interceptor", "jakarta.interceptor")
                .replace("javax.jms", "jakarta.jms")
                .replace("javax.json", "jakarta.json")
                .replace("javax.jws", "jakarta.jws")
                .replace("javax.mail", "jakarta.mail")
                .replace("javax.persistence", "jakarta.persistence")
                .replace("javax.resource", "jakarta.resource")
                .replace("javax.security.auth.message", "jakarta.security.auth.message")
                .replace("javax.security.enterprise", "jakarta.security.enterprise")
                .replace("javax.security.jacc", "jakarta.security.jacc")
                .replace("javax.servlet", "jakarta.servlet")
                .replace("javax.validation", "jakarta.validation")
                .replace("javax.websocket", "jakarta.websocket")
                .replace("javax.ws.rs", "jakarta.ws.rs")
                .replace("javax.xml.bind", "jakarta.xml.bind")
                .replace("javax.xml.soap", "jakarta.xml.soap")
                .replace("javax.xml.ws", "jakarta.xml.ws")
                .replace("javax\\.faces", "jakarta\\.faces") // in some javascript files
                .get();

        {
            final String name = new File(path).getName();
            final String replacement = replacements.get(name);
            if (replacement != null) {
                log.debug(String.format("Replaced %s with %s", path, replacement));
                inputStream = IO.read(new File(replacement));
            }
        }

        IO.copy(inputStream, outputStream);
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

    public void complete() {
        final List<Clazz> appliedPatches = classes.stream()
                .filter(Clazz::isApplied)
                .collect(Collectors.toList());

        final List<Clazz> unappliedPatches = classes.stream()
                .filter(not(Clazz::isApplied))
                .collect(Collectors.toList());

        final int applied = appliedPatches.stream()
                .map(Clazz::getApplied)
                .reduce(Integer::sum)
                .orElse(0);

        log.info(String.format("Applied %s patches to %s locations", appliedPatches.size(), applied));
        appliedPatches.stream()
                .map(Clazz::getName)
                .map(s -> "  " + s)
                .forEach(log::debug);

        if (unappliedPatches.size() > 0) {
            final String message = String.format("Failed to apply %s patches", unappliedPatches.size());
            log.error(message);
            unappliedPatches.stream()
                    .map(Clazz::getName)
                    .map(s -> "  " + s)
                    .forEach(log::error);
            throw new UnappliedPatchesException(unappliedPatches);
        }
    }

    public static class Jar {
        private static final ThreadLocal<Jar> current = ThreadLocal.withInitial(Jar::new);

        private final Set<Clazz> patches = new HashSet<>();
        private final String name;
        private final Jar parent;

        private Jar() {
            this.name = "";
            this.parent = null;
        }

        private Jar(final String name, Jar parent) {
            this.name = name;
            this.parent = parent;
        }

        public String getName() {
            return name;
        }

        public String getPath() {
            if (parent == null) return name;
            return parent.getPath() + "/" + name;
        }

        public boolean hasPatches() {
            return patches.size() > 0;
        }

        public static Jar current() {
            return current.get();
        }

        public static Jar enter(final String name) {
            final Jar old = current.get();
            current.set(new Jar(name, old));
            return old;
        }

        public static void exit(final Jar oldJar) {
            current.set(oldJar);
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

    private boolean isPatched(final String path, final Jar jar) {
        for (final Clazz clazz : classes) {
            if (path.startsWith(clazz.getPrefix())) {
                jar.patch(clazz, classes);
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
