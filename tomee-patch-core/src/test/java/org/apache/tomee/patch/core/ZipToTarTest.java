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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.Test;
import org.tomitribe.util.Archive;
import org.tomitribe.util.IO;
import org.tomitribe.util.PrintString;

import java.io.File;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.assertEquals;

public class ZipToTarTest {

    @Test
    public void createTarGz() throws Exception {
        final File zip = Archive.archive()
                .add("color/red/crimson.txt", "DC143C")
                .add("color/red/ruby.txt", "9b111e")
                .add("color/blue/navy.sh", "000080")
                .add("color/green/forest.txt", "228b22")
                .add("index.txt", "red,green,blue")
                .toJar();


        final File tarGz = ZipToTar.toTarGz(zip);

        final PrintString out = new PrintString();
        {

            final InputStream in = IO.read(tarGz);
            final GZIPInputStream gzipIn = new GZIPInputStream(in);
            final TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn);

            TarArchiveEntry tarEntry = null;
            while ((tarEntry = (TarArchiveEntry) tarIn.getNextEntry()) != null) {
                out.printf("%s %s %s%n", tarEntry.getSize(), tarEntry.getMode(), tarEntry.getName());
            }
        }

        assertEquals("" +
                "14 33188 index.txt\n" +
                "6 33188 color/red/crimson.txt\n" +
                "6 493 color/blue/navy.sh\n" +
                "6 33188 color/red/ruby.txt\n" +
                "6 33188 color/green/forest.txt\n", out.toString());
    }

}
