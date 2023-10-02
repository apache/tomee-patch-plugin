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

import org.junit.Test;
import org.tomitribe.util.Archive;
import org.tomitribe.util.Hex;
import org.tomitribe.util.IO;

import java.io.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

public class DontModifyJarsTest {

    public static final int DEFAULT_BUFFER_SIZE = 8192;

    @Test
    public void transformWithoutModification() throws Exception {
        transformWithoutModificationHelper(false);
    }

    @Test
    public void transformWithoutModificationSkipTransform() throws Exception {
        transformWithoutModificationHelper(true);
    }

    public void transformWithoutModificationHelper(boolean skipTransform) throws Exception {

        final String jarName = "bcprov-jdk15on-1.69.jar";
        final File testJar = Archive.archive()
                .add("index.txt", "red,green,blue")
                .toJar();

        final String testJarHash = sha512(testJar);

        final File zipFile = Archive.archive()
                .add("README.txt", "hi")
                .add(jarName, testJar).toJar();

        Transformation transformation = new Transformation(new ArrayList<>(), new File("does not exist"), null, null, null, new NullLog(), skipTransform);
        File transformedZip = transformation.transformArchive(zipFile);

        final String testJarHashTransformed = sha512FromJarInsideZip(jarName, transformedZip);
        assertEquals("SHA512 checksum shouldn't change if nothing is modified", testJarHash, testJarHashTransformed);

        assertEquals("SHA512 checksum shouldn't change if nothing is modified", sha512(zipFile), sha512(transformedZip));
    }


    private String sha512FromJarInsideZip(final String name, final File transformedZip) throws IOException {
        try (ZipFile zip = new ZipFile(transformedZip)) {
            Enumeration<? extends ZipEntry> content = zip.entries();
            for (Enumeration<? extends ZipEntry> f = content; f.hasMoreElements(); ) {
                ZipEntry entry = f.nextElement();

                if (entry.getName().equals(name)) {
                    File jar = new File(entry.getName());
                    copyInputStreamToFile(zip.getInputStream(entry), jar);
                    jar.deleteOnExit();
                    return sha512(jar);
                }
            }
        }
        return null;
    }

    private static String sha512(final File file) {
        return hash("SHA-512", file);
    }

    private static String hash(final String type, File file) {
        try {
            final MessageDigest digest = MessageDigest.getInstance(type);
            try (final InputStream inputStream = IO.read(file)) {
                final DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest);
                IO.copy(digestInputStream, IO.IGNORE_OUTPUT);
                return Hex.toString(digest.digest());
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unknown algorithm " + type, e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copyInputStreamToFile(InputStream inputStream, File file) throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(file, false)) {
            int read;
            byte[] bytes = new byte[DEFAULT_BUFFER_SIZE];
            while ((read = inputStream.read(bytes)) != -1) {
                outputStream.write(bytes, 0, read);
            }
        }

    }

}

