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
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.compiler.Compiler;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerMessage;
import org.codehaus.plexus.compiler.CompilerResult;
import org.codehaus.plexus.compiler.manager.CompilerManager;
import org.codehaus.plexus.compiler.manager.NoSuchCompilerException;
import org.tomitribe.util.Files;
import org.tomitribe.util.Zips;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mojo(name = "run", requiresDependencyResolution = ResolutionScope.RUNTIME_PLUS_SYSTEM, defaultPhase = LifecyclePhase.PACKAGE, requiresProject = true, threadSafe = true)
public class PatchMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project.build.directory}", required = true)
    private File outputDirectory;

    @Parameter(defaultValue = "${project.basedir}/src/main/patch", required = true)
    private File patchSourceDirectory;

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

    /**
     * Sets the executable of the compiler to use when {@link #fork} is <code>true</code>.
     */
    @Parameter(property = "maven.compiler.executable")
    private String executable;

    /**
     * Version of the compiler to use, ex. "1.3", "1.5", if {@link #fork} is set to <code>true</code>.
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
    @Parameter(defaultValue = "${project.build.directory}", required = true, readonly = true)
    private File buildDirectory;

    /**
     * The -encoding argument for the Java compiler.
     *
     * @since 2.1
     */
    @Parameter(property = "encoding", defaultValue = "${project.build.sourceEncoding}")
    private String encoding;

    @Component
    private MavenProjectHelper projectHelper;

    @Component
    protected ArtifactFactory factory;

    @Component
    protected ArtifactResolver resolver;

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
            final List<Artifact> artifacts = Stream.of(getSourceArtifacts())
                    .filter(artifact -> artifact.getFile().getName().contains("jakartaee9"))
                    .collect(Collectors.toList());

            final File archives = new File(outputDirectory, "patch");
            Files.mkdir(archives);

            Compiler compiler;

            getLog().debug("Using compiler '" + compilerId + "'.");

            try {
                compiler = compilerManager.getCompiler(compilerId);
            } catch (NoSuchCompilerException e) {
                throw new MojoExecutionException("No such compiler '" + e.getCompilerId() + "'.");
            }

            Toolchain tc = getToolchain();
            if (tc != null) {
                getLog().info("Toolchain in maven-compiler-plugin: " + tc);
                //TODO somehow shaky dependency between compilerId and tool executable.
                executable = tc.findTool(compilerId);
            }

            final CompilerConfiguration compilerConfiguration = new CompilerConfiguration();
            compilerConfiguration.setOutputLocation(outputDirectory.getAbsolutePath());
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
            compilerConfiguration.setSourceLocations(getPatchSourceLocations());
            compilerConfiguration.setAnnotationProcessors(null);
            compilerConfiguration.setSourceEncoding(encoding);
            compilerConfiguration.setFork(true);
            compilerConfiguration.setExecutable(executable);
            compilerConfiguration.setWorkingDirectory(basedir);
            compilerConfiguration.setCompilerVersion(compilerVersion);
            compilerConfiguration.setBuildDirectory(buildDirectory);
            compilerConfiguration.setOutputFileName(null);

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

            if (true && !compilerResult.isSuccess()) {
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

            for (final Artifact artifact : artifacts) {
                Zips.unzip(artifact.getFile(), archives);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Error occurred during execution", e);
        }
    }

    private List<String> getPatchSourceLocations() {
        return Collections.singletonList(patchSourceDirectory.getAbsolutePath());
    }

    private void patch(final Artifact artifact) throws IOException {
        final File file = artifact.getFile();

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

}
