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

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.tomitribe.util.IO;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ZipToTar {

    public static File toTarGz(final File zip) throws Exception {

        final String tarGzName = zip.getName().replaceAll("\\.(zip|jar)$", ".tar.gz");
        final File tarGz = new File(zip.getParentFile(), tarGzName);

        try (final InputStream in = IO.read(zip); final TarArchiveOutputStream tarOut = tar(tarGz)) {

            tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            final ZipInputStream zipIn = new ZipInputStream(in);

            ZipEntry zipEntry;
            while ((zipEntry = zipIn.getNextEntry()) != null) {
                final String name = zipEntry.getName();

                // The ZipEntry often shows -1 as the size and the
                // TarArchiveOutputStream API requires us to know the
                // exact size before we start writing.  So we have to
                // buffer everything in memory first to learn the size
                final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                IO.copy(zipIn, buffer);
                final byte[] bytes = buffer.toByteArray();

                final TarArchiveEntry tarEntry = new TarArchiveEntry(name);

                // Set the size and date
                tarEntry.setSize(bytes.length);
                tarEntry.setModTime(zipEntry.getLastModifiedTime().toMillis());

                // Mark any shell scripts as executable
                if (name.endsWith(".sh")) {
                    tarEntry.setMode(493);
                }

                // Finally out the Entry into the archive
                // Any attributes set on tarEntry after this
                // point are ignored.
                tarOut.putArchiveEntry(tarEntry);

                IO.copy(bytes, tarOut);
                tarOut.closeArchiveEntry();
            }

            tarOut.finish();
        }

        return tarGz;
    }

    private static TarArchiveOutputStream tar(final File tarGz) throws IOException {
        return new TarArchiveOutputStream(new GZIPOutputStream(IO.write(tarGz)));
    }
}
