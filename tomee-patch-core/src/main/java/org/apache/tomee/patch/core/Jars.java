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

import org.tomitribe.util.IO;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class Jars {

    public static boolean isSigned(final File jarFile) throws IOException {
        // first approach is to look for signature files
        if (!listJarContent(jarFile, "^META-INF/[^.]+.(SF|DSA|RSA)$").isEmpty()) {
            return true;
        }

        // parse the digest and see if we can find entries with a Digest suffix
        if (hasDigest(jarFileContent(jarFile, "META-INF/MANIFEST.MF"))) {
            return true;
        }

        // we can also look for Digest entries in the manifest as a String if we can't parse it
        return IO.slurp(jarFileContent(jarFile, "META-INF/MANIFEST.MF")).contains("-Digest");
    }

    public static List<String> listJarContent(final File jarFile) throws IOException {
        return listJarContent(jarFile, null);
    }

    public static List<String> listJarContent(final ZipInputStream zipInputStream) throws IOException {
        return listJarContent(zipInputStream, null);
    }

    public static List<String> listJarContent(final File jarFile, final String matchPattern) throws IOException {
        return new ZipFile(jarFile).stream()
                                   .filter(zipEntry -> matchPattern == null || zipEntry.getName().matches(matchPattern))
                                   .filter(Objects::nonNull)
                                   .map(ZipEntry::getName)
                                   .collect(Collectors.toList());
    }

    public static List<String> listJarContent(final ZipInputStream zipInputStream, final String matchPattern) throws IOException {
        final List<String> result = new ArrayList<>();
        ZipEntry entry;
        while ((entry = zipInputStream.getNextEntry()) != null) {
            final String name = entry.getName();
            if (matchPattern != null && !name.matches(matchPattern)) {
                continue;
            }
            result.add(name);
        }
        return result;
    }

    public static InputStream jarFileContent(final ZipInputStream zipInputStream, final String name) throws IOException {
        ZipEntry entry;
        while ((entry = zipInputStream.getNextEntry()) != null) {
            final String entryName = entry.getName();
            if (entryName.contains(name)) {
                return zipInputStream;
            }

        }
        return null;
    }

    public static InputStream jarFileContent(final File jarFile, final String name) throws IOException {
        return new ZipFile(jarFile).stream()
                                   .filter(zipEntry -> zipEntry.getName().contains(name))
                                   .findFirst()
                                   .map(zipEntry -> {
                                       try {
                                           return new ZipFile(jarFile).getInputStream(zipEntry);

                                       } catch (final IOException e) {
                                           throw new RuntimeException(e);
                                       }
                                   }).orElse(null);
    }

    public static boolean hasDigest(final InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return false;
        }
        final Manifest man = new Manifest(inputStream);
        for(Map.Entry<String, Attributes> entry: man.getEntries().entrySet()) {
            for(Object attrkey: entry.getValue().keySet()) {
                if (attrkey instanceof Attributes.Name && attrkey.toString().contains("-Digest"))
                    return true;
            }
        }
        return false;
    }
}
