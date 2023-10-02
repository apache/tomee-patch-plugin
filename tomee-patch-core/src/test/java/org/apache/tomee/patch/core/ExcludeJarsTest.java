package org.apache.tomee.patch.core;

import org.junit.Before;
import org.junit.Test;
import org.tomitribe.util.Archive;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExcludeJarsTest {
    public static final int DEFAULT_BUFFER_SIZE = 8192;
    public Skips customSkips = new Skips();

    @Before
    public void prepareLists() {
        customSkips.getJars().put("eclipselink-3.0.0.jar", "org.eclipse.persistence:eclipselink:jar:3.0.0");
        customSkips.getJars().put("bcprov-jdk15on-1.69.jar", "org.bouncycastle:bcprov-jdk15on:jar:1.69");
    }

    @Test
    public void transformWithJarExclusions() throws Exception {
        final String jarSignatureFileName = "META-INF/sigTest.DSA";
        final String jarName = "bcprov-jdk15on-1.69.jar";
        final File testJar = Archive.archive()
                .add(jarSignatureFileName, "DC143C")
                .add("index.txt", "red,green,blue")
                .toJar();

        final File zipFile = Archive.archive()
                .add("README.txt", "hi")
                .add(jarName, testJar).toJar();

        final Transformation transformation = new Transformation(new ArrayList<Clazz>(), new File("does not exist"), null, customSkips, null, new NullLog(), false);
        final File transformedJar = transformation.transformArchive(zipFile);
        assertTrue(obtainJarContent(transformedJar).contains(jarSignatureFileName));
    }

    @Test
    public void transformWithoutJarExclusions() throws Exception {
        final String jarSignatureFileName = "META-INF/sigTest.DSA";
        final String jarName = "jdbc.jar";

        final File testJar = Archive.archive()
                .add(jarSignatureFileName, "DC143C")
                .add("index.txt", "red,green,blue")
                .toJar();

        final File zipFile = Archive.archive()
                .add("README.txt", "hi")
                .add(jarName, testJar).toJar();

        final Transformation transformation = new Transformation(new ArrayList<Clazz>(), new File("does not exist"), null, customSkips, null, new NullLog(), false);
        final File transformedJar = transformation.transformArchive(zipFile);
        assertFalse(obtainJarContent(transformedJar).contains(jarSignatureFileName));
    }

    private List obtainJarContent(File transformedJar) throws IOException {
        final List<String> jarFileList = new ArrayList<String>();

        //Iterating over the zip files
        final ZipFile zip = new ZipFile(transformedJar);
        final Enumeration content = zip.entries();
        for (Enumeration f = content; f.hasMoreElements(); ) {
            final ZipEntry entry = (ZipEntry) f.nextElement();
            //System.out.println(entry.getName());

            if (entry.getName().endsWith(".jar")) {

                //Iterating over the jar foun in the zip file
                final File jar = new File(entry.getName());
                copyInputStreamToFile(zip.getInputStream(entry), jar);
                final JarFile jarFile = new JarFile(jar);
                final Enumeration innerEntries = jarFile.entries();
                for (Enumeration e = innerEntries; e.hasMoreElements(); ) {
                    final JarEntry file = (JarEntry) e.nextElement();
                    //System.out.println("  - " +file.getName());
                    jarFileList.add(file.getName());
                }
                jarFile.close();
                jar.deleteOnExit();
            }
        }
        zip.close();
        return jarFileList;
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

