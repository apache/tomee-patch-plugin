package org.apache.tomee.patch.core;

import org.junit.Before;
import org.junit.Test;
import org.tomitribe.util.Archive;
import org.tomitribe.util.IO;
import org.tomitribe.util.Mvn;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.apache.tomee.patch.core.Jars.hasDigest;
import static org.apache.tomee.patch.core.Jars.jarFileContent;
import static org.apache.tomee.patch.core.Jars.listJarContent;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExcludeJarsTest {
    public static final int DEFAULT_BUFFER_SIZE = 8192;
    public Skips customSkips = new Skips();

    @Before
    public void prepareLists(){
        customSkips.getJars().put("eclipselink-3.0.0.jar","org.eclipse.persistence:eclipselink:jar:3.0.0");
        customSkips.getJars().put("bcprov-jdk15on-1.69.jar","org.bouncycastle:bcprov-jdk15on:jar:1.69");
    }

    @Test
    public void checkJarIsSigned() throws IOException {
        final String coordinates = "org.bouncycastle:bcprov-jdk15on:jar:1.69";
        final File jarFile = Mvn.mvn(coordinates);

        assertTrue(listJarContent(jarFile).size() > 10); // BC Prov has much more

        assertTrue(listJarContent(jarFile, "^META-INF/[^.]+.(SF|DSA|RSA)$").size() > 1);

        System.out.println(IO.slurp(jarFileContent(jarFile, "META-INF/MANIFEST.MF")));

        assertTrue(IO.slurp(jarFileContent(jarFile, "META-INF/MANIFEST.MF")).contains("-Digest"));

        // This call will throw a java.lang.SecurityException if someone has tampered
        // with the signature of _any_ element of the JAR file.
        // Alas, it will proceed without a problem if the JAR file is not signed at all
        final JarFile jar = new JarFile(jarFile);
        try (final InputStream is = jar.getInputStream(jar.getEntry("META-INF/MANIFEST.MF"))) {
            assertTrue(hasDigest(is));
        }

        assertTrue(Jars.isSigned(jarFile));
    }

    @Test
    public void isTransformedNotSkippedForSignedJars() throws IOException {
        final String coordinates = "org.bouncycastle:bcprov-jdk15on:jar:1.69";
        final String jarName = "bcprov-jdk15on-1.69.jar";
        final File realRepoJar = Mvn.mvn(coordinates);
        final File tempFile = Files.createTempFile("bc", "temp").toFile();
        IO.copy(realRepoJar, tempFile);

        final File zipFile = Archive.archive()
                                    .add("README.txt", "hi")
                                    .add(jarName, tempFile).toJar();

        assertTrue(listJarContent(tempFile).size() > 10); // BC Prov has much more

        assertTrue(listJarContent(tempFile, "^META-INF/[^.]+.(SF|DSA|RSA)$").size() > 1);

        // System.out.println(IO.slurp(jarFileContent(jarFile, "META-INF/MANIFEST.MF")));

        assertTrue(IO.slurp(jarFileContent(tempFile, "META-INF/MANIFEST.MF")).contains("-Digest"));

        // This call will throw a java.lang.SecurityException if someone has tampered
        // with the signature of _any_ element of the JAR file.
        // Alas, it will proceed without a problem if the JAR file is not signed at all
        final JarFile jar = new JarFile(tempFile);
        try (final InputStream is = jar.getInputStream(jar.getEntry("META-INF/MANIFEST.MF"))) {
            assertTrue(hasDigest(is));
        }

        assertTrue(Jars.isSigned(tempFile));

        Transformation transformation = new Transformation(new ArrayList<Clazz>(), new File("does not exist"),
                                                           null, null, null, new NullLog(),
                                                           false, true);
        final File transformedZip = transformation.transformArchive(zipFile);

        // With skipSigned to true, we'll skip signed jars so they remain valid
        assertTrue(obtainJarContent(transformedZip).contains("META-INF/BC1024KE.SF"));
        assertTrue(obtainJarContent(transformedZip).contains("META-INF/BC1024KE.DSA"));

        assertValidSignedJar(jarName, transformedZip);
    }

    private void assertValidSignedJar(final String jarName, final File transformedZip) throws IOException {
        final ZipFile zip = new ZipFile(transformedZip);
        final InputStream inputStream = zip.getInputStream(new ZipEntry(jarName));
        final File tempTransformedJar = Files.createTempFile("bc", "temp").toFile();
        IO.copy(inputStream, tempTransformedJar);
        final JarFile jarFile = new JarFile(tempTransformedJar);

        final Manifest manifest = jarFile.getManifest();
        System.out.println(manifest.toString());

        // This call will throw a java.lang.SecurityException if someone has tampered
        // with the signature of _any_ element of the JAR file.
        // Alas, it will proceed without a problem if the JAR file is not signed at all
        try (final InputStream is = jarFile.getInputStream(jarFile.getEntry("META-INF/MANIFEST.MF"))) {
            System.out.println(IO.slurp(is));
        }
    }

    @Test
    public void isTransformedSkippedForSignedJars() throws IOException {
        final String coordinates = "org.bouncycastle:bcprov-jdk15on:jar:1.69";
        final String jarName = "bcprov-jdk15on-1.69.jar";
        final File realRepoJar = Mvn.mvn(coordinates);
        final File tempFile = Files.createTempFile("bc", "temp").toFile();
        IO.copy(realRepoJar, tempFile);

        final File zipFile = Archive.archive()
                                    .add("README.txt", "hi")
                                    .add(jarName, tempFile).toJar();

        assertTrue(Jars.isSigned(tempFile));

        Transformation transformation = new Transformation(new ArrayList<Clazz>(), new File("does not exist"),
                                                           null, null, null, new NullLog(),
                                                           false, false);
        final File transformedZip = transformation.transformArchive(zipFile);

        // when skipSigned is false, we'll try to transform them, BUT ...
        // we need to remove the signature jars, we need to also filter the manifest to remove Digest entries
        assertFalse(obtainJarContent(transformedZip).contains("META-INF/BC1024KE.SF"));
        assertFalse(obtainJarContent(transformedZip).contains("META-INF/BC1024KE.DSA"));

        assertValidSignedJar(jarName, transformedZip);
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

        Transformation transformation = new Transformation(new ArrayList<Clazz>(), new File("does not exist"),
                                                           null, customSkips, null, new NullLog(),
                                                           false, false);
        File transformedJar = transformation.transformArchive(zipFile);
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

        Transformation transformation = new Transformation(new ArrayList<Clazz>(), new File("does not exist"),
                                                           null, customSkips, null, new NullLog(),
                                                           false, false);
        File transformedJar = transformation.transformArchive(zipFile);
        assertFalse(obtainJarContent(transformedJar).contains(jarSignatureFileName));
    }

    private List obtainJarContent(File transformedJar) throws IOException {
        List<String> jarFileList = new ArrayList<String>();

        //Iterating over the zip files
        ZipFile zip = new ZipFile(transformedJar);
        Enumeration content = zip.entries();
        for (Enumeration f = content; f.hasMoreElements(); ) {
            ZipEntry entry = (ZipEntry) f.nextElement();
            //System.out.println(entry.getName());

            if (entry.getName().endsWith(".jar")) {

                //Iterating over the jar foun in the zip file
                File jar = new File(entry.getName());
                copyInputStreamToFile(zip.getInputStream(entry),jar);
                JarFile jarFile = new JarFile(jar);
                Enumeration innerEntries = jarFile.entries();
                for (Enumeration e = innerEntries; e.hasMoreElements(); ) {
                    JarEntry file = (JarEntry) e.nextElement();
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

