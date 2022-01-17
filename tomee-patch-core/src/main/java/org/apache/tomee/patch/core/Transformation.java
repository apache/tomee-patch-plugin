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
import org.tomitribe.util.Mvn;
import org.tomitribe.util.dir.Dir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.tomitribe.jkta.util.Predicates.not;

public class Transformation {

    private final List<Clazz> classes = new ArrayList<Clazz>();
    private final Log log;
    private final Replacements replacements;
    private final Skips skips;
    private final Additions additions;
    private final Boolean skipTransform;
    private final File patchResources;

    public Transformation() {
        this.log = new NullLog();
        this.replacements = new Replacements();
        this.skips = new Skips();
        this.additions = new Additions();
        this.skipTransform = false;
        this.patchResources = new File("does not exist");
    }


    public Transformation(final List<Clazz> classes, final File patchResources, final Replacements replacements, final Skips skips, final Additions additions, final Log log, final Boolean skipTransform) {
        this.classes.addAll(classes);
        this.log = log;
        this.replacements = replacements == null ? new Replacements() : replacements;
        this.skips = skips == null ? new Skips() : skips;
        this.additions = additions == null ? new Additions() : additions;
        this.patchResources = patchResources;
        this.skipTransform = skipTransform;
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
        {
            final String jar = new File(name).getName();
            final String replacement = replacements.getJars().get(jar);
            if (replacement != null) {
                final File file = Mvn.mvn(replacement);
                if (!file.exists()) {
                    throw new ReplacementNotFoundException("jar", jar, file.getAbsolutePath());
                }
                log.info(String.format("Replaced %s", name));
                IO.copy(file, outputStream);

                IO.copy(inputStream, skipped);

                return;
            }
        }

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
                        if(isExcludedJar(path)){
                            IO.copy(zipInputStream, zipOutputStream);
                        }else{
                            scanJar(path, zipInputStream, zipOutputStream);
                        }
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

            final String jarName = new File(jar.getName()).getName();
            if (additions.getResources().containsKey(jarName) && patchResources.exists()) {
                final String regex = additions.getResources().get(jarName);
                final Pattern pattern = getPattern(regex);

                final Dir dir = Dir.of(Dir.class, patchResources);
                final List<Resource> resources = dir.files()
                        .map(file -> Resource.relative(patchResources, file))
                        .filter(resource -> resource.matches(pattern))
                        .collect(Collectors.toList());

                for (final Resource resource : resources) {
                    log.info("Adding " + resource.getPath());

                    final ZipEntry newEntry = new ZipEntry(resource.getPath());
                    zipOutputStream.putNextEntry(newEntry);

                    // Run any transformations on these classes as well
                    IO.copy(IO.read(resource.getFile()), zipOutputStream);

                    zipOutputStream.closeEntry();
                }
            }

            zipOutputStream.finish();
        } catch (IOException e) {
            throw new IOException(jar.getPath() + e.getMessage(), e);
        } finally {
            Jar.exit(oldJar);
        }
    }

    private Pattern getPattern(final String regex) {
        try {
            return Pattern.compile(regex);
        } catch (Exception e) {
            log.error(String.format("Invalid pattern: '%s'", regex));
            return null;
        }
    }

    public static class Resource {
        private final File file;
        private final String path;

        public Resource(final File file, final String path) {
            this.file = file;
            this.path = path;
        }

        public File getFile() {
            return file;
        }

        public String getPath() {
            return path;
        }

        public boolean matches(final Pattern pattern) {
            return pattern != null && pattern.matcher(path).matches();
        }

        public static Resource relative(final File parent, final File file) {
            final int i = parent.getAbsolutePath().length() + 1;
            final String path = file.getAbsolutePath().substring(i);
            return new Resource(file, path);
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
        return name;
        //return name.replace("resources/javax.faces", "resources/jakarta.faces");
    }

    private boolean copyUnmodified(final String path) {
        if (path.endsWith("META-INF/DEPENDENCIES")) return true;
        if (path.endsWith("META-INF/dependencies.xml")) return true;
        if (path.endsWith("changelog.html")) return true;
        if (path.endsWith("RELEASE-NOTES.txt")) return true;
        if (path.endsWith("pom.xml")) return true;
        return false;
    }

    private boolean isExcludedJar(final String path) {
        if (skips != null) {
            Map<String, String> skipsJars = skips.getJars();
            if (!skipsJars.isEmpty()) {
                for (Map.Entry<String, String> set : skipsJars.entrySet()) {
                    if (path.contains(set.getKey())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void scanResource(final String path, InputStream inputStream, final OutputStream outputStream) throws IOException {

        {
            final String name = new File(path).getName();
            final String replacement = replacements.getResources().get(name);
            if (replacement != null) {
                log.info(String.format("Replaced %s", path));
                final File file = new File(replacement);
                if (!file.exists()) {
                    throw new ReplacementNotFoundException("resource", path, file.getAbsolutePath());
                }
                inputStream = IO.read(file);
                IO.copy(inputStream, outputStream);
                return;
            }
        }

        // in case we don't want to apply any transformation. Only replacement will happen
        if (skipTransform) {
            IO.copy(inputStream, outputStream);
            return;
        }


        if (path.endsWith("openwebbeans.properties")) {
            inputStream = StreamBuilder.create(inputStream)
                    .replace("org.apache.webbeans.proxy.mapping.javax.enterprise", "org.apache.webbeans.proxy.mapping.jakarta.enterprise")
                    .replace("\n        /javax, \\\n", "\n        /javax, \\\n        /jakarta, \\\n")
                    .replace("javax.enterprise.inject.allowProxying.classes", "jakarta.enterprise.inject.allowProxying.classes")
                    .get();
        }

        inputStream = StreamBuilder.create(inputStream)
                .replace("javax.activation", "jakarta.activation")
                .replace("javax.annotation", "jakarta.annotation")
                .replace("javax.batch", "jakarta.batch")
                .replace("javax.decorator", "jakarta.decorator")
                .replace("javax.ejb", "jakarta.ejb")
                .replace("javax.el", "jakarta.el")
                .replace("javax.enterprise", "jakarta.enterprise")
                .replace("javax\\.faces", "jakarta\\.faces")
                .replace("javax.inject", "jakarta.inject")
                .replace("javax.interceptor", "jakarta.interceptor")
                .replace("javax.jms", "jakarta.jms")
                .replace("javax.json", "jakarta.json")
                .replace("javax.json.bind", "jakarta.json.bind")
                .replace("javax.jws", "jakarta.jws")
                .replace("javax.mail", "jakarta.mail")
                .replace("javax.persistence", "jakarta.persistence")
                .replace("javax.resource", "jakarta.resource")
                .replace("javax.security.auth.message", "jakarta.security.auth.message")
                .replace("javax.security.enterprise", "jakarta.security.enterprise")
                .replace("javax.security.jacc", "jakarta.security.jacc")
                .replace("javax.servlet", "jakarta.servlet")
                .replace("javax.transaction", "jakarta.transaction")
                .replace("javax.validation", "jakarta.validation")
                .replace("javax.websocket", "jakarta.websocket")
                .replace("javax.ws.rs", "jakarta.ws.rs")
                .replace("javax.xml.bind", "jakarta.xml.bind")
                .replace("javax.xml.soap", "jakarta.xml.soap")
                .replace("javax.xml.ws", "jakarta.xml.ws")

                // These sub packages to the above must be renamed back
                .replace("jakarta.annotation.process", "javax.annotation.process")
                .replace("jakarta.enterprise.deploy", "javax.enterprise.deploy")
                .replace("jakarta.transaction.xa", "javax.transaction.xa")

                // Packages that are often falsely renamed
                // Exceptions to the exceptions

                .replace("jakarta.accessibility", "javax.accessibility")
                .replace("jakarta.annotation.processing", "javax.annotation.processing")
                .replace("jakarta.cache", "javax.cache")
                .replace("jakarta.crypto", "javax.crypto")
                .replace("jakarta.imageio", "javax.imageio")
                .replace("jakarta.jdo", "javax.jdo")
                .replace("jakarta.jmdns", "javax.jmdns")
                .replace("jakarta.lang", "javax.lang")
                .replace("jakarta.lang.model", "javax.lang.model")
                .replace("jakarta.management", "javax.management")
                .replace("jakarta.naming", "javax.naming")
                .replace("jakarta.net", "javax.net")
                .replace("jakarta.portlet", "javax.portlet")
                .replace("jakarta.print", "javax.print")
                .replace("jakarta.rmi", "javax.rmi")
                .replace("jakarta.script", "javax.script")
                .replace("jakarta.security.Principal", "javax.security.Principal")
                .replace("jakarta.security.auth.AuthPermission", "javax.security.auth.AuthPermission")
                .replace("jakarta.security.auth.Deprecated", "javax.security.auth.Deprecated")
                .replace("jakarta.security.auth.DestroyFailedException", "javax.security.auth.DestroyFailedException")
                .replace("jakarta.security.auth.Destroyable", "javax.security.auth.Destroyable")
                .replace("jakarta.security.auth.LdapPrincipal", "javax.security.auth.LdapPrincipal")
                .replace("jakarta.security.auth.NTDomainPrincipal", "javax.security.auth.NTDomainPrincipal")
                .replace("jakarta.security.auth.NTNumericCredential", "javax.security.auth.NTNumericCredential")
                .replace("jakarta.security.auth.NTSid", "javax.security.auth.NTSid")
                .replace("jakarta.security.auth.NTSidDomainPrincipal", "javax.security.auth.NTSidDomainPrincipal")
                .replace("jakarta.security.auth.NTSidGroupPrincipal", "javax.security.auth.NTSidGroupPrincipal")
                .replace("jakarta.security.auth.NTSidPrimaryGroupPrincipal", "javax.security.auth.NTSidPrimaryGroupPrincipal")
                .replace("jakarta.security.auth.NTSidUserPrincipal", "javax.security.auth.NTSidUserPrincipal")
                .replace("jakarta.security.auth.NTUserPrincipal", "javax.security.auth.NTUserPrincipal")
                .replace("jakarta.security.auth.PolicyFile", "javax.security.auth.PolicyFile")
                .replace("jakarta.security.auth.PrincipalComparator", "javax.security.auth.PrincipalComparator")
                .replace("jakarta.security.auth.PrivateCredentialPermission", "javax.security.auth.PrivateCredentialPermission")
                .replace("jakarta.security.auth.RefreshFailedException", "javax.security.auth.RefreshFailedException")
                .replace("jakarta.security.auth.Refreshable", "javax.security.auth.Refreshable")
                .replace("jakarta.security.auth.SolarisNumericGroupPrincipal", "javax.security.auth.SolarisNumericGroupPrincipal")
                .replace("jakarta.security.auth.SolarisNumericUserPrincipal", "javax.security.auth.SolarisNumericUserPrincipal")
                .replace("jakarta.security.auth.SolarisPrincipal", "javax.security.auth.SolarisPrincipal")
                .replace("jakarta.security.auth.Subject", "javax.security.auth.Subject")
                .replace("jakarta.security.auth.SubjectDomainCombiner", "javax.security.auth.SubjectDomainCombiner")
                .replace("jakarta.security.auth.UnixNumericGroupPrincipal", "javax.security.auth.UnixNumericGroupPrincipal")
                .replace("jakarta.security.auth.UnixNumericUserPrincipal", "javax.security.auth.UnixNumericUserPrincipal")
                .replace("jakarta.security.auth.UnixPrincipal", "javax.security.auth.UnixPrincipal")
                .replace("jakarta.security.auth.UserPrincipal", "javax.security.auth.UserPrincipal")
                .replace("jakarta.security.auth.X500Principal", "javax.security.auth.X500Principal")
                .replace("jakarta.security.auth.callback", "javax.security.auth.callback")
                .replace("jakarta.security.auth.kerberos", "javax.security.auth.kerberos")
                .replace("jakarta.security.auth.login", "javax.security.auth.login")
                .replace("jakarta.security.auth.spi", "javax.security.auth.spi")
                .replace("jakarta.security.auth.subject", "javax.security.auth.subject")
                .replace("jakarta.security.auth.x500", "javax.security.auth.x500")
                .replace("jakarta.security.cert", "javax.security.cert")
                .replace("jakarta.security.sasl", "javax.security.sasl")
                .replace("jakarta.smartcardio", "javax.smartcardio")
                .replace("jakarta.sound", "javax.sound")
                .replace("jakarta.sql", "javax.sql")
                .replace("jakarta.swing", "javax.swing")
                .replace("jakarta.tools", "javax.tools")
                .replace("jakarta.transaction.xa", "javax.transaction.xa")
                .replace("jakarta.wsdl", "javax.wsdl")
                .replace("jakarta.xml.XML", "javax.xml.XML")
                .replace("jakarta.xml.access", "javax.xml.access")
                .replace("jakarta.xml.catalog", "javax.xml.catalog")
                .replace("jakarta.xml.crypto", "javax.xml.crypto")
                .replace("jakarta.xml.datatype", "javax.xml.datatype")
                .replace("jakarta.xml.messaging", "javax.xml.messaging")
                .replace("jakarta.xml.namespace", "javax.xml.namespace")
                .replace("jakarta.xml.parser", "javax.xml.parser")
                .replace("jakarta.xml.parsers", "javax.xml.parsers")
                .replace("jakarta.xml.registry", "javax.xml.registry")
                .replace("jakarta.xml.rpc", "javax.xml.rpc")
                .replace("jakarta.xml.stream", "javax.xml.stream")
                .replace("jakarta.xml.transform", "javax.xml.transform")
                .replace("jakarta.xml.validation", "javax.xml.validation")
                .replace("jakarta.xml.xpath", "javax.xml.xpath")
                .replace("javax.enterprise.deploy-api", "jakarta.enterprise.deploy-api")

                .get();

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

    private void scanClass(final InputStream in, final OutputStream outputStream) throws IOException {

        // in case we don't want to apply any transformation. Only replacement will happen
        if (skipTransform) {
            IO.copy(in, outputStream);
            return;
        }

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
        final String classPackage = path.endsWith(".class") ? Clazz.asPackage(path) : null;

        boolean patchedClass = false;
        for (final Clazz clazz : classes) {
            if (path.equals(clazz.getName()) ||
                    path.startsWith(clazz.getPrefix() + "$")) {

                jar.patch(clazz, classes);
                patchedClass = true;
            }

            if (classPackage != null && classPackage.equals(clazz.getPackge())) {
                jar.patch(clazz, classes);
            }
        }
        return patchedClass;
    }

    private static final OutputStream skipped = new OutputStream() {
        @Override
        public void write(final int b) {
        }
    };
}
