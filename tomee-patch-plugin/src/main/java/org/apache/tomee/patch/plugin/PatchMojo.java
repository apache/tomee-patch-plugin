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
package org.apache.tomee.patch.plugin;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.artifact.AttachedArtifact;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.apache.tomee.patch.core.Clazz;
import org.apache.tomee.patch.core.Is;
import org.apache.tomee.patch.core.Replacements;
import org.apache.tomee.patch.core.Transformation;
import org.apache.tomee.patch.core.ZipToTar;
import org.codehaus.plexus.compiler.Compiler;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerMessage;
import org.codehaus.plexus.compiler.CompilerResult;
import org.codehaus.plexus.compiler.manager.CompilerManager;
import org.codehaus.plexus.compiler.manager.NoSuchCompilerException;
import org.tomitribe.jkta.usage.Dir;
import org.tomitribe.jkta.util.Paths;
import org.tomitribe.util.Files;
import org.tomitribe.util.IO;
import org.tomitribe.util.Zips;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mojo(name = "run", requiresDependencyResolution = ResolutionScope.RUNTIME_PLUS_SYSTEM, defaultPhase = LifecyclePhase.PACKAGE, requiresProject = true, threadSafe = true)
public class PatchMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project.basedir}/src/patch/java", required = true)
    private List<File> patchSources;

    @Parameter
    private List<String> sourceExcludes = new ArrayList<>();

    /**
     * Regex to identify which archives should be matched
     */
    @Parameter(defaultValue = "jakartaee9.*\\.zip", required = true)
    private String select;

    /**
     * <p>
     * Specify the requirements for this jdk toolchain.
     * This overrules the toolchain selected by the maven-toolchain-plugin.
     * </p>
     * <strong>note:</strong> requires at least Maven 3.3.1
     *
     * @since 3.6
     */
    @Parameter
    private Map<String, String> jdkToolchain;

    @Parameter
    private Replacements replace;

    @Parameter(defaultValue = "false")
    private Boolean createTarGz;

    @Parameter(defaultValue = "false")
    private Boolean skipTransform;

    /**
     * Sets the executable of the compiler to use when fork is <code>true</code>.
     */
    @Parameter(property = "maven.compiler.executable")
    private String executable;

    /**
     * Version of the compiler to use, ex. "1.3", "1.5", if fork is set to <code>true</code>.
     */
    @Parameter(property = "maven.compiler.compilerVersion")
    private String compilerVersion;

    /**
     * The directory to run the compiler from if fork is true.
     */
    @Parameter(defaultValue = "${basedir}", required = true, readonly = true)
    private File basedir;

    /**
     * The target directory of the compiler if fork is true.
     */
    @Parameter(defaultValue = "${project.build.directory}/patch-classes", required = true, readonly = true)
    private File buildDirectory;

    /**
     * The directory where we will extract the zips being patched so we can compile the
     * patch source against the jars contained within.
     */
    @Parameter(defaultValue = "${project.build.directory}/patch-classpath", required = true)
    private File patchClasspathDirectory;

    @Parameter(defaultValue = "${project.build.directory}/patch-sources", required = true)
    private File patchSourceDirectory;

    /**
     * The -encoding argument for the Java compiler.
     *
     * @since 2.1
     */
    @Parameter(property = "encoding", defaultValue = "${project.build.sourceEncoding}")
    private String encoding;

    /**
     * Plexus compiler manager.
     */
    @Component
    private CompilerManager compilerManager;

    @Component
    private ToolchainManager toolchainManager;

    /**
     * <p>The -source argument for the Java compiler.</p>
     */
    @Parameter(property = "maven.compiler.source", defaultValue = "1.8")
    protected String source;

    /**
     * <p>The -target argument for the Java compiler.</p>
     */
    @Parameter(property = "maven.compiler.target", defaultValue = "1.8")
    protected String target;

    /**
     * The compiler id of the compiler to use. See this
     * <a href="non-javac-compilers.html">guide</a> for more information.
     */
    @Parameter(property = "maven.compiler.compilerId", defaultValue = "javac")
    private String compilerId;

    /**
     * Main execution point of the plugin. This looks at the attached artifacts, and runs the transformer on them.
     * @throws MojoExecutionException Thrown if there is an error during plugin execution
     */
    public void execute() throws MojoExecutionException, CompilationFailureException {
        try {
            Files.mkdir(patchClasspathDirectory);

            // Select the zip files and jars we'll be potentially patching
            final List<Artifact> artifacts = getPatchArtifacts();

            // Extract any zips and return a list of jars
            final List<File> jars = prepareJars(artifacts);

            compile(jars);

            final List<Clazz> clazzes = classes();

            final Transformation transformation = new Transformation(clazzes, replace, new MavenLog(getLog()), skipTransform);
            for (final Artifact artifact : artifacts) {
                final File file = artifact.getFile();
                getLog().debug("Patching " + file.getAbsolutePath());
                final File patched = transformation.transformArchive(file);
                IO.copy(patched, file);

                if (createTarGz && file.getName().endsWith(".zip")) {
                    final File tarGz;
                    try {
                        tarGz = ZipToTar.toTarGz(file);
                    } catch (Exception e) {
                        getLog().error("Failed to create tar.gz from " + file.getAbsolutePath(), e);
                        continue;
                    }

                    final String classifier = artifact.getClassifier();
                    final AttachedArtifact attachedArtifact = new AttachedArtifact(project.getArtifact(), "tar.gz", classifier, project.getArtifact().getArtifactHandler());
                    attachedArtifact.setFile(tarGz);
                    attachedArtifact.setResolved(true);
                    project.addAttachedArtifact(attachedArtifact);
                }
            }

            transformation.complete();
        } catch (IOException e) {
            throw new MojoExecutionException("Error occurred during execution", e);
        }
    }

    private List<Clazz> classes() {
        return Dir.from(buildDirectory).files()
                .filter(file -> file.getName().endsWith(".class"))
                .map(file -> new Clazz(Paths.childPath(buildDirectory, file), file))
                .collect(Collectors.toList());
    }

    /**
     * Any zip files contained in the Artifact set should be extracted
     * Any jar files contained in the Artifact set will be returned as-is
     */
    private List<File> prepareJars(final List<Artifact> artifacts) throws IOException {

        // Extract all zip, war, ear, rar files.  Do not extract jar files.
        for (final Artifact artifact : artifacts) {
            if (isZip(artifact.getFile()) && !isJar(artifact.getFile())) {
                Zips.unzip(artifact.getFile(), patchClasspathDirectory);
            }
        }

        // Collect a list of jars
        final List<File> jars = new ArrayList<>();

        // Add any artifacts that are already jars
        artifacts.stream()
                .map(Artifact::getFile)
                .filter(File::isFile)
                .filter(this::isJar)
                .forEach(jars::add);

        // Add any extracted files that are jars
        Dir.from(patchClasspathDirectory)
                .files()
                .filter(File::isFile)
                .filter(this::isJar)
                .forEach(jars::add);

        return jars;
    }

    private boolean isJar(final File file) {
        return file.getName().endsWith(".jar");
    }

    private static boolean isZip(final File file) {
        return new Is.Zip().accept(file);
    }

    private List<Artifact> getPatchArtifacts() {
        final Predicate<String> match = Pattern.compile(select).asPredicate();
        final Artifact[] available = getSourceArtifacts();

        final List<Artifact> selected = Stream.of(available)
                .filter(artifact -> match.test(artifact.getFile().getName()))
                .collect(Collectors.toList());

        if (selected.size() == 0) {
            final String message = String.format("No artifacts matched expression '%s'.  %s available artifacts:", select, available.length);
            getLog().error(message);
            Arrays.stream(available)
                    .map(artifact -> artifact.getFile().getName())
                    .forEach(s -> getLog().error(" - " + s));

            throw new NoMatchingArtifactsException(select);
        }
        return selected;
    }

    private void compile(final List<File> jars) throws MojoExecutionException, CompilationFailureException {

        getLog().debug("Using compiler '" + compilerId + "'.");

        final Compiler compiler;

        try {
            compiler = compilerManager.getCompiler(compilerId);
        } catch (NoSuchCompilerException e) {
            throw new MojoExecutionException("No such compiler '" + e.getCompilerId() + "'.");
        }

        final Toolchain tc = getToolchain();
        if (tc != null) {
            getLog().info("Toolchain in maven-compiler-plugin: " + tc);
            //TODO somehow shaky dependency between compilerId and tool executable.
            executable = tc.findTool(compilerId);
        }

        Files.mkdir(patchSourceDirectory);
        for (final File patchSource : patchSources) {
            if (!patchSource.exists()) {
                final String message = "Patch source directory does not exist: " + patchSource.getAbsolutePath();
                getLog().error(message);
                throw new MojoExecutionException(message);
            }
            if (!patchSource.isDirectory()) {
                final String message = "Patch source directory is not a directory: " + patchSource.getAbsolutePath();
                getLog().error(message);
                throw new MojoExecutionException(message);
            }
        }
        patchSources.forEach(file -> copy(file, file, patchSourceDirectory));


        final CompilerConfiguration compilerConfiguration = new CompilerConfiguration();
        compilerConfiguration.setOutputLocation(buildDirectory.getAbsolutePath());
        compilerConfiguration.setOptimize(false);
        compilerConfiguration.setDebug(true);
        compilerConfiguration.setParameters(false);
        compilerConfiguration.setVerbose(false);
        compilerConfiguration.setShowWarnings(false);
        compilerConfiguration.setFailOnWarning(false);
        compilerConfiguration.setShowDeprecation(false);
        compilerConfiguration.setSourceVersion(source);
        compilerConfiguration.setTargetVersion(target);
        compilerConfiguration.setReleaseVersion(null);
        compilerConfiguration.setProc(null);
        compilerConfiguration.setSourceLocations(Collections.singletonList(patchSourceDirectory.getAbsolutePath()));
        compilerConfiguration.setAnnotationProcessors(null);
        compilerConfiguration.setSourceEncoding(encoding);
        compilerConfiguration.setFork(true);
        compilerConfiguration.setExecutable(executable);
        compilerConfiguration.setWorkingDirectory(basedir);
        compilerConfiguration.setCompilerVersion(compilerVersion);
        compilerConfiguration.setBuildDirectory(buildDirectory);
        compilerConfiguration.setOutputFileName(null);

        // Add each jar as a classpath entry
        jars.stream()
                .map(File::getAbsolutePath)
                .forEach(compilerConfiguration::addClasspathEntry);

        // Now we can compile!
        final CompilerResult compilerResult;
        try {
            compilerResult = compiler.performCompile(compilerConfiguration);
        } catch (Exception e) {
            throw new MojoExecutionException("Fatal error compiling", e);
        }

        List<CompilerMessage> warnings = new ArrayList<>();
        List<CompilerMessage> errors = new ArrayList<>();
        List<CompilerMessage> others = new ArrayList<>();
        for (CompilerMessage message : compilerResult.getCompilerMessages()) {
            if (message.getKind() == CompilerMessage.Kind.ERROR) {
                errors.add(message);
            } else if (message.getKind() == CompilerMessage.Kind.WARNING
                    || message.getKind() == CompilerMessage.Kind.MANDATORY_WARNING) {
                warnings.add(message);
            } else {
                others.add(message);
            }
        }

        if (!compilerResult.isSuccess()) {
            for (CompilerMessage message : others) {
                assert message.getKind() != CompilerMessage.Kind.ERROR
                        && message.getKind() != CompilerMessage.Kind.WARNING
                        && message.getKind() != CompilerMessage.Kind.MANDATORY_WARNING;
                getLog().info(message.toString());
            }
            if (!warnings.isEmpty()) {
                getLog().info("-------------------------------------------------------------");
                getLog().warn("COMPILATION WARNING : ");
                getLog().info("-------------------------------------------------------------");
                for (CompilerMessage warning : warnings) {
                    getLog().warn(warning.toString());
                }
                getLog().info(warnings.size() + ((warnings.size() > 1) ? " warnings " : " warning"));
                getLog().info("-------------------------------------------------------------");
            }

            if (!errors.isEmpty()) {
                getLog().info("-------------------------------------------------------------");
                getLog().error("COMPILATION ERROR : ");
                getLog().info("-------------------------------------------------------------");
                for (CompilerMessage error : errors) {
                    getLog().error(error.toString());
                }
                getLog().info(errors.size() + ((errors.size() > 1) ? " errors " : " error"));
                getLog().info("-------------------------------------------------------------");
            }

            if (!errors.isEmpty()) {
                throw new CompilationFailureException(errors);
            } else {
                throw new CompilationFailureException(warnings);
            }
        } else {
            for (CompilerMessage message : compilerResult.getCompilerMessages()) {
                switch (message.getKind()) {
                    case NOTE:
                    case OTHER:
                        getLog().info(message.toString());
                        break;

                    case ERROR:
                        getLog().error(message.toString());
                        break;

                    case MANDATORY_WARNING:
                    case WARNING:
                    default:
                        getLog().warn(message.toString());
                        break;
                }
            }
        }
    }

    private void copy(final File root, final File src, final File dest) {
        copy:
        for (final File file : src.listFiles()) {

            final String path = file.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);

            for (final String exclude : sourceExcludes) {
                if (path.matches(exclude)) {
                    getLog().debug("Exclude source file: " + file.getAbsolutePath());
                    continue copy;
                }
            }

            if (file.isDirectory()) {
                final File dir = new File(dest, file.getName());
                Files.mkdir(dir);
                copy(root, file, dir);
            } else if (file.isFile()) {
                try {
                    IO.copy(file, new File(dest, file.getName()));
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot copy file " + file.getAbsolutePath(), e);
                }
            }
        }
    }

    //TODO remove the part with ToolchainManager lookup once we depend on
    //3.0.9 (have it as prerequisite). Define as regular component field then.
    protected final Toolchain getToolchain() {
        Toolchain tc = null;

        if (jdkToolchain != null) {
            // Maven 3.3.1 has plugin execution scoped Toolchain Support
            try {
                Method getToolchainsMethod =
                        toolchainManager.getClass().getMethod("getToolchains", MavenSession.class, String.class,
                                Map.class);

                @SuppressWarnings("unchecked")
                List<Toolchain> tcs =
                        (List<Toolchain>) getToolchainsMethod.invoke(toolchainManager, session, "jdk",
                                jdkToolchain);

                if (tcs != null && !tcs.isEmpty()) {
                    tc = tcs.get(0);
                }
            } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException
                    | InvocationTargetException e) {
                // ignore
            }
        }

        if (tc == null) {
            tc = toolchainManager.getToolchainFromBuildContext("jdk", session);
        }

        return tc;
    }


    /**
     * Gets the source artifacts that should be transformed
     * @return an array to artifacts to be transformed
     */
    public Artifact[] getSourceArtifacts() {
        List<Artifact> artifactList = new ArrayList<Artifact>();
        if (project.getArtifact() != null && project.getArtifact().getFile() != null) {
            artifactList.add(project.getArtifact());
        }

        for (final Artifact attachedArtifact : project.getAttachedArtifacts()) {
            if (attachedArtifact.getFile() != null) {
                artifactList.add(attachedArtifact);
            }
        }

        return artifactList.toArray(new Artifact[0]);
    }

    private static class NoMatchingArtifactsException extends RuntimeException {
        public NoMatchingArtifactsException(final String select) {
            super(String.format("No artifacts matched expression '%s'", select));
        }
    }
}
