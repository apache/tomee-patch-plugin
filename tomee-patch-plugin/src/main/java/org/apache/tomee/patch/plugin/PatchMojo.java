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
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.artifact.AttachedArtifact;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.settings.Settings;
import org.apache.maven.shared.transfer.artifact.ArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.dependencies.DefaultDependableCoordinate;
import org.apache.maven.shared.transfer.dependencies.DependableCoordinate;
import org.apache.maven.shared.transfer.dependencies.resolve.DependencyResolver;
import org.apache.maven.shared.transfer.dependencies.resolve.DependencyResolverException;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.apache.tomee.patch.core.Additions;
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
import org.codehaus.plexus.util.StringUtils;
import org.tomitribe.jkta.usage.Dir;
import org.tomitribe.jkta.util.Paths;
import org.tomitribe.swizzle.stream.StreamBuilder;
import org.tomitribe.util.Files;
import org.tomitribe.util.IO;
import org.tomitribe.util.Mvn;
import org.tomitribe.util.Zips;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mojo(name = "run", requiresDependencyResolution = ResolutionScope.RUNTIME_PLUS_SYSTEM, defaultPhase = LifecyclePhase.PACKAGE, requiresProject = true, threadSafe = true)
public class PatchMojo extends AbstractMojo {

    private static final Pattern ALT_REPO_SYNTAX_PATTERN = Pattern.compile("(.+)::(.*)::(.+)");

    @Component
    private ArtifactResolver artifactResolver;

    @Component
    private DependencyResolver dependencyResolver;

    @Component
    private ArtifactHandlerManager artifactHandlerManager;

    /**
     * Map that contains the layouts.
     */
    @Component(role = ArtifactRepositoryLayout.class)
    private Map<String, ArtifactRepositoryLayout> repositoryLayouts;

    /**
     * The repository system.
     */
    @Component
    private RepositorySystem repositorySystem;

    /**
     * Repositories in the format id::[layout]::url or just url, separated by comma. ie.
     * central::default::https://repo.maven.apache.org/maven2,myrepo::::https://repo.acme.com,https://repo.acme2.com
     */
    @Parameter(property = "remoteRepositories")
    private String remoteRepositories;

    /**
     *
     */
    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true, required = true)
    private List<ArtifactRepository> pomRemoteRepositories;

    /**
     * Download transitively, retrieving the specified artifact and all of its dependencies.
     */
    @Parameter(property = "transitive", defaultValue = "true")
    private boolean transitive = true;

    /**
     * Skip plugin execution completely.
     *
     * @since 2.7
     */
    @Parameter(property = "mdep.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project.basedir}/src/patch/java", required = true)
    private List<File> patchSources;

    @Parameter(defaultValue = "${project.basedir}/src/patch/resources", required = true)
    private List<File> patchResources;

    @Parameter
    private List<String> sourceExcludes = new ArrayList<>();

    @Parameter
    private List<String> dependencies = new ArrayList<>();

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

    @Parameter
    private Additions add;

    @Parameter(defaultValue = "false")
    private Boolean createTarGz;

    @Parameter(defaultValue = "false")
    private Boolean skipTransform;

    @Parameter(defaultValue = "false")
    private Boolean transformSources;

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

    @Parameter(defaultValue = "${project.build.directory}/patch-resources", required = true)
    private File patchResourceDirectory;

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
     *
     * @throws MojoExecutionException Thrown if there is an error during plugin execution
     */
    public void execute() throws MojoExecutionException, CompilationFailureException {
        try {
            Files.mkdir(patchClasspathDirectory);

            // Select the zip files and jars we'll be potentially patching
            final List<Artifact> artifacts = getPatchArtifacts();

            prepareResources();

            // Extract any zips and return a list of jars
            final List<File> jars = prepareJars(artifacts);

            compile(jars);

            final List<Clazz> clazzes = classes();

            final Transformation transformation = new Transformation(clazzes, patchResourceDirectory, replace, add, new MavenLog(getLog()), skipTransform);
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
        } catch (IOException | MojoFailureException e) {
            throw new MojoExecutionException("Error occurred during execution", e);
        }
    }

    private void prepareResources() throws MojoExecutionException {
        final File defaultPatchResources = new File(basedir, "src/patch/resources");

        Files.mkdir(patchResourceDirectory);
        for (final File patchResource : patchResources) {
            if (patchResource == null) continue;
            if (!patchResource.exists()) {

                if (patchResource.getAbsolutePath().equals(defaultPatchResources.getAbsolutePath())) {
                    // If the default directory does not exist, the user likely did not explicitly
                    // ask for it.  Just silently skip it.
                    continue;
                }

                final String message = "Patch resource directory does not exist: " + patchResource.getAbsolutePath();
                getLog().error(message);
                throw new MojoExecutionException(message);
            }
            if (!patchResource.isDirectory()) {
                final String message = "Patch resource directory is not a directory: " + patchResource.getAbsolutePath();
                getLog().error(message);
                throw new MojoExecutionException(message);
            }
        }
        patchResources.stream()
                .filter(Objects::nonNull)
                .filter(File::exists)
                .forEach(file -> copy(file, file, patchResourceDirectory));
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

    private void compile(final List<File> jars) throws MojoExecutionException, MojoFailureException {
        final List<File> files = resolve(dependencies);

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

        final File defaultPatchSources = new File(basedir, "src/patch/java");
        Files.mkdir(patchSourceDirectory);
        for (final File patchSource : patchSources) {
            if (patchSource == null) continue;
            if (!patchSource.exists()) {

                if (patchSource.getAbsolutePath().equals(defaultPatchSources.getAbsolutePath())) {
                    // If the default directory does not exist, the user likely did not explicitly
                    // ask for it.  Just silently skip it.
                    continue;
                }

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
        patchSources.stream()
                .filter(Objects::nonNull)
                .filter(File::exists)
                .forEach(file -> copy(file, file, patchSourceDirectory));


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

        files.stream()
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

    private List<File> resolve(final List<String> dependencies) throws MojoFailureException, MojoExecutionException {
        final List<File> resolvedDependencies = new ArrayList<File>();
        for (final String dependency : dependencies) {
            final File file = resolve(dependency);
            resolvedDependencies.add(file);
        }
        return resolvedDependencies;
    }

    private File resolve(final String gav) throws MojoFailureException, MojoExecutionException {
        try {
            return Mvn.mvn(gav);
        } catch (Exception e) {
            return download(gav);
        }
    }

    private void copy(final File root, final File src, final File dest) {
        copy:
        for (final File file : src.listFiles()) {

            final String path = file.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);

            for (final String exclude : sourceExcludes) {
                if (path.replace(File.separatorChar, '/').matches(exclude)) {
                    getLog().debug("Exclude source file: " + file.getAbsolutePath());
                    continue copy;
                }
            }

            if (file.isDirectory()) {
                final File dir = new File(dest, file.getName());
                Files.mkdir(dir);
                copy(root, file, dir);
            } else if (file.isFile()) {
                try (InputStream in = IO.read(file)) {
                    if (!skipTransform && transformSources) {
                        IO.copy(updateImports(in), new File(dest, file.getName()));
                    } else {
                        IO.copy(in, new File(dest, file.getName()));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot copy file " + file.getAbsolutePath(), e);
                }
            }
        }
    }

    private InputStream updateImports(final InputStream in) {
        return StreamBuilder.create(in)
                .replace("javax.activation", "jakarta.activation")
                .replace("javax.annotation", "jakarta.annotation")
                .replace("javax.batch", "jakarta.batch")
                .replace("javax.decorator", "jakarta.decorator")
                .replace("javax.ejb", "jakarta.ejb")
                .replace("javax.el", "jakarta.el")
                .replace("javax.enterprise", "jakarta.enterprise")
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
                .get()
                ;
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
     *
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

    public File download(final String gav) throws MojoExecutionException, MojoFailureException {

        final DefaultDependableCoordinate coordinate = mvn(gav);

        ArtifactRepositoryPolicy always =
                new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS,
                        ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN);

        List<ArtifactRepository> repoList = new ArrayList<>();

        if (pomRemoteRepositories != null) {
            repoList.addAll(pomRemoteRepositories);
        }

        if (remoteRepositories != null) {
            // Use the same format as in the deploy plugin id::layout::url
            String[] repos = StringUtils.split(remoteRepositories, ",");
            for (String repo : repos) {
                repoList.add(parseRepository(repo, always));
            }
        }

        try {
            ProjectBuildingRequest buildingRequest =
                    new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());

            Settings settings = session.getSettings();
            repositorySystem.injectMirror(repoList, settings.getMirrors());
            repositorySystem.injectProxy(repoList, settings.getProxies());
            repositorySystem.injectAuthentication(repoList, settings.getServers());

            buildingRequest.setRemoteRepositories(repoList);

            if (transitive) {
                getLog().info("Resolving " + coordinate + " with transitive dependencies");
                dependencyResolver.resolveDependencies(buildingRequest, coordinate, null);
            } else {
                getLog().info("Resolving " + coordinate);
                artifactResolver.resolveArtifact(buildingRequest, toArtifactCoordinate(coordinate));
            }
        } catch (ArtifactResolverException | DependencyResolverException e) {
            throw new MojoExecutionException("Couldn't download artifact: " + e.getMessage(), e);
        }

        return Mvn.mvn(gav);
    }

    private ArtifactCoordinate toArtifactCoordinate(DependableCoordinate dependableCoordinate) {
        ArtifactHandler artifactHandler = artifactHandlerManager.getArtifactHandler(dependableCoordinate.getType());
        DefaultArtifactCoordinate artifactCoordinate = new DefaultArtifactCoordinate();
        artifactCoordinate.setGroupId(dependableCoordinate.getGroupId());
        artifactCoordinate.setArtifactId(dependableCoordinate.getArtifactId());
        artifactCoordinate.setVersion(dependableCoordinate.getVersion());
        artifactCoordinate.setClassifier(dependableCoordinate.getClassifier());
        artifactCoordinate.setExtension(artifactHandler.getExtension());
        return artifactCoordinate;
    }

    ArtifactRepository parseRepository(String repo, ArtifactRepositoryPolicy policy)
            throws MojoFailureException {
        // if it's a simple url
        String id = "temp";
        ArtifactRepositoryLayout layout = getLayout("default");
        String url = repo;

        // if it's an extended repo URL of the form id::layout::url
        if (repo.contains("::")) {
            Matcher matcher = ALT_REPO_SYNTAX_PATTERN.matcher(repo);
            if (!matcher.matches()) {
                throw new MojoFailureException(repo, "Invalid syntax for repository: " + repo,
                        "Invalid syntax for repository. Use \"id::layout::url\" or \"URL\".");
            }

            id = matcher.group(1).trim();
            if (!StringUtils.isEmpty(matcher.group(2))) {
                layout = getLayout(matcher.group(2).trim());
            }
            url = matcher.group(3).trim();
        }
        return new MavenArtifactRepository(id, url, layout, policy, policy);
    }

    private ArtifactRepositoryLayout getLayout(String id)
            throws MojoFailureException {
        ArtifactRepositoryLayout layout = repositoryLayouts.get(id);

        if (layout == null) {
            throw new MojoFailureException(id, "Invalid repository layout", "Invalid repository layout: " + id);
        }

        return layout;
    }

    public static DefaultDependableCoordinate mvn(final String coordinates) {
        final String[] parts = coordinates.split(":");

        // org.apache.tomee:apache-tomee:zip:plus:7.1.0
        if (parts.length == 5) {
            final String group = parts[0];
            final String artifact = parts[1];
            final String packaging = parts[2];
            final String classifier = parts[3];
            final String version = parts[4];
            return mvn(group, artifact, version, packaging, classifier);
        }

        // org.apache.tomee:tomee-util:jar:7.1.0
        if (parts.length == 4) {
            final String group = parts[0];
            final String artifact = parts[1];
            final String packaging = parts[2];
            final String version = parts[3];
            return mvn(group, artifact, version, packaging, null);
        }

        throw new IllegalArgumentException("Unsupported coordinates (GAV): " + coordinates);
    }

    private static DefaultDependableCoordinate mvn(final String group, final String artifact,
                                                   final String version, final String packaging,
                                                   final String classifier) {
        final DefaultDependableCoordinate coordinate = new DefaultDependableCoordinate();
        coordinate.setArtifactId(artifact);
        coordinate.setClassifier(classifier);
        coordinate.setGroupId(group);
        coordinate.setVersion(version);
        coordinate.setType(packaging);
        return coordinate;
    }

}
