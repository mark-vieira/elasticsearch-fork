/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.gradle.internal;

import org.elasticsearch.gradle.BwcVersions;
import org.elasticsearch.gradle.Version;
import org.elasticsearch.gradle.info.BuildParams;
import org.elasticsearch.gradle.info.GlobalBuildInfoPlugin;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

import javax.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Arrays.stream;

/**
 * We want to be able to do BWC tests for unreleased versions without relying on and waiting for snapshots.
 * For this we need to check out and build the unreleased versions.
 * Since these depend on the current version, we can't name the Gradle projects statically, and don't know what the
 * unreleased versions are when Gradle projects are set up, so we use "build-unreleased-version-*" as placeholders
 * and configure them to build various versions here.
 */
public class InternalDistributionBwcSetupPlugin implements InternalPlugin {

    private ProviderFactory providerFactory;

    @Inject
    public InternalDistributionBwcSetupPlugin(ProviderFactory providerFactory) {
        this.providerFactory = providerFactory;
    }

    @Override
    public void apply(Project project) {
        project.getRootProject().getPluginManager().apply(GlobalBuildInfoPlugin.class);
        BuildParams.getBwcVersions()
            .forPreviousUnreleased(
                (BwcVersions.UnreleasedVersionInfo unreleasedVersion) -> {
                    configureBwcProject(project.project(unreleasedVersion.gradleProjectPath), unreleasedVersion);
                }
            );
    }

    private void configureBwcProject(Project project, BwcVersions.UnreleasedVersionInfo versionInfo) {
        Provider<BwcVersions.UnreleasedVersionInfo> versionInfoProvider = providerFactory.provider(() -> versionInfo);
        Provider<File> checkoutDir = versionInfoProvider.map(info -> new File(project.getBuildDir(), "bwc/checkout-" + info.branch));
        BwcSetupExtension bwcSetupExtension = project.getExtensions()
            .create("bwcSetup", BwcSetupExtension.class, project, versionInfoProvider, checkoutDir);
        BwcGitExtension gitExtension = project.getPlugins().apply(InternalBwcGitPlugin.class).getGitExtension();
        Provider<Version> bwcVersion = versionInfoProvider.map(info -> info.version);
        gitExtension.setBwcVersion(versionInfoProvider.map(info -> info.version));
        gitExtension.setBwcBranch(versionInfoProvider.map(info -> info.branch));
        gitExtension.getCheckoutDir().set(checkoutDir);

        // we want basic lifecycle tasks like `clean` here.
        project.getPlugins().apply(LifecycleBasePlugin.class);

        TaskProvider<Task> buildBwcTaskProvider = project.getTasks().register("buildBwc");
        List<DistributionProject> distributionProjects = resolveArchiveProjects(checkoutDir.get(), bwcVersion.get());

        for (DistributionProject distributionProject : distributionProjects) {
            createBuildBwcTask(
                bwcSetupExtension,
                project,
                bwcVersion,
                distributionProject.name,
                distributionProject.projectPath,
                distributionProject.expectedBuildArtifact,
                buildBwcTaskProvider,
                distributionProject.getAssembleTaskName()
            );

            registerBwcDistributionArtifacts(project, distributionProject);
        }

        // Create build tasks for the JDBC driver used for compatibility testing
        String jdbcProjectDir = "x-pack/plugin/sql/jdbc";

        DistributionProjectArtifact jdbcProjectArtifact = new DistributionProjectArtifact(
            new File(checkoutDir.get(), jdbcProjectDir + "/build/distributions/x-pack-sql-jdbc-" + bwcVersion.get() + "-SNAPSHOT.jar"),
            null
        );

        createBuildBwcTask(
            bwcSetupExtension,
            project,
            bwcVersion,
            "jdbc",
            jdbcProjectDir,
            jdbcProjectArtifact,
            buildBwcTaskProvider,
            "assemble"
        );
    }

    private void registerBwcDistributionArtifacts(Project bwcProject, DistributionProject distributionProject) {
        String projectName = distributionProject.name;
        String buildBwcTask = buildBwcTaskName(projectName);

        registerDistributionArchiveArtifact(bwcProject, distributionProject, buildBwcTask);
        File expectedExpandedDistDirectory = distributionProject.expectedBuildArtifact.expandedDistDir;
        if (expectedExpandedDistDirectory != null) {
            String expandedDistConfiguration = "expanded-" + projectName;
            bwcProject.getConfigurations().create(expandedDistConfiguration);
            bwcProject.getArtifacts().add(expandedDistConfiguration, expectedExpandedDistDirectory, artifact -> {
                artifact.setName("elasticsearch");
                artifact.builtBy(buildBwcTask);
                artifact.setType("directory");
            });
        }
    }

    private void registerDistributionArchiveArtifact(Project bwcProject, DistributionProject distributionProject, String buildBwcTask) {
        File distFile = distributionProject.expectedBuildArtifact.distFile;
        String artifactFileName = distFile.getName();
        String artifactName = artifactFileName.contains("oss") ? "elasticsearch-oss" : "elasticsearch";

        String suffix = artifactFileName.endsWith("tar.gz") ? "tar.gz" : artifactFileName.substring(artifactFileName.length() - 3);
        int archIndex = artifactFileName.indexOf("x86_64");

        bwcProject.getConfigurations().create(distributionProject.name);
        bwcProject.getArtifacts().add(distributionProject.name, distFile, artifact -> {
            artifact.setName(artifactName);
            artifact.builtBy(buildBwcTask);
            artifact.setType(suffix);

            String classifier = "";
            if (archIndex != -1) {
                int osIndex = artifactFileName.lastIndexOf('-', archIndex - 2);
                classifier = "-" + artifactFileName.substring(osIndex + 1, archIndex - 1) + "-x86_64";
            }
            artifact.setClassifier(classifier);
        });
    }

    private static List<DistributionProject> resolveArchiveProjects(File checkoutDir, Version bwcVersion) {
        List<String> projects = new ArrayList<>();
        // All active BWC branches publish default and oss variants of rpm and deb packages
        projects.addAll(asList("deb", "rpm", "oss-deb", "oss-rpm"));

        if (bwcVersion.onOrAfter("7.0.0")) { // starting with 7.0 we bundle a jdk which means we have platform-specific archives
            projects.addAll(asList("oss-windows-zip", "windows-zip", "oss-darwin-tar", "darwin-tar", "oss-linux-tar", "linux-tar"));
        } else { // prior to 7.0 we published only a single zip and tar archives for oss and default distributions
            projects.addAll(asList("oss-zip", "zip", "tar", "oss-tar"));
        }

        return projects.stream().map(name -> {
            String baseDir = "distribution" + (name.endsWith("zip") || name.endsWith("tar") ? "/archives" : "/packages");
            String classifier = "";
            String extension = name;
            if (bwcVersion.onOrAfter("7.0.0")) {
                if (name.contains("zip") || name.contains("tar")) {
                    int index = name.lastIndexOf('-');
                    String baseName = name.startsWith("oss-") ? name.substring(4, index) : name.substring(0, index);
                    classifier = "-" + baseName + "-x86_64";
                    extension = name.substring(index + 1);
                    if (extension.equals("tar")) {
                        extension += ".gz";
                    }
                } else if (name.contains("deb")) {
                    classifier = "-amd64";
                } else if (name.contains("rpm")) {
                    classifier = "-x86_64";
                }
            }
            return new DistributionProject(name, baseDir, bwcVersion, classifier, extension, checkoutDir);
        }).collect(Collectors.toList());
    }

    private static String buildBwcTaskName(String projectName) {
        return "buildBwc"
            + stream(projectName.split("-")).map(i -> i.substring(0, 1).toUpperCase(Locale.ROOT) + i.substring(1))
                .collect(Collectors.joining());
    }

    static void createBuildBwcTask(
        BwcSetupExtension bwcSetupExtension,
        Project project,
        Provider<Version> bwcVersion,
        String projectName,
        String projectPath,
        DistributionProjectArtifact projectArtifact,
        TaskProvider<Task> bwcTaskProvider,
        String assembleTaskName
    ) {
        String bwcTaskName = buildBwcTaskName(projectName);
        bwcSetupExtension.bwcTask(bwcTaskName, c -> {
            boolean useNativeExpanded = projectArtifact.expandedDistDir != null;
            File expectedOutputFile = useNativeExpanded ? projectArtifact.expandedDistDir : projectArtifact.distFile;
            c.getInputs().file(new File(project.getBuildDir(), "refspec"));
            if (useNativeExpanded) {
                c.getOutputs().dir(expectedOutputFile);
            } else {
                c.getOutputs().files(expectedOutputFile);
            }
            c.getOutputs().cacheIf("BWC distribution caching is disabled on 'master' branch", task -> {
                String gitBranch = System.getenv("GIT_BRANCH");
                return BuildParams.isCi() && (gitBranch == null || gitBranch.endsWith("master") == false);
            });
            c.args(projectPath.replace('/', ':') + ":" + assembleTaskName);
            if (project.getGradle().getStartParameter().isBuildCacheEnabled()) {
                c.args("--build-cache");
            }
            c.doLast(task -> {
                if (expectedOutputFile.exists() == false) {
                    System.out.println("expectedOutputFile.getParentFile().exists() = " + expectedOutputFile.getParentFile().exists());
                    if (expectedOutputFile.getParentFile().exists()) {
                        Arrays.asList(expectedOutputFile.getParentFile().list()).forEach(f -> System.out.println("f = " + f));
                    }
                    throw new InvalidUserDataException(
                        "Building " + bwcVersion.get() + " didn't generate expected artifact " + expectedOutputFile
                    );
                }
            });

        });
        bwcTaskProvider.configure(t -> t.dependsOn(bwcTaskName));
    }

    /**
     * Represents a distribution project (distribution/**)
     * we build from a bwc Version in a cloned repository
     */
    private static class DistributionProject {
        final String name;
        final File checkoutDir;
        final String projectPath;
        final boolean nativeExtractedSupport;
        final DistributionProjectArtifact expectedBuildArtifact;

        DistributionProject(String name, String baseDir, Version version, String classifier, String extension, File checkoutDir) {
            this.name = name;
            this.checkoutDir = checkoutDir;
            this.projectPath = baseDir + "/" + name;
            this.nativeExtractedSupport = version.onOrAfter("7.11.0") && (name.endsWith("zip") || name.endsWith("tar"));
            this.expectedBuildArtifact = new DistributionProjectArtifact(
                new File(
                    checkoutDir,
                    baseDir
                        + "/"
                        + name
                        + "/build/distributions/elasticsearch-"
                        + (name.startsWith("oss") ? "oss-" : "")
                        + version
                        + "-SNAPSHOT"
                        + classifier
                        + "."
                        + extension
                ),
                nativeExtractedSupport ? new File(checkoutDir, baseDir + "/" + name + "/build/install") : null
            );
        }

        /**
         * Newer elasticsearch branches allow building extracted bwc elasticsearch versions
         * from source without the overhead of creating an archive by using assembleExtracted instead of assemble.
         * */
        public String getAssembleTaskName() {
            return nativeExtractedSupport ? "extractedAssemble" : "assemble";
        }
    }

    private static class DistributionProjectArtifact {
        final File distFile;
        final File expandedDistDir;

        DistributionProjectArtifact(File distFile, File expandedDistDir) {
            this.distFile = distFile;
            this.expandedDistDir = expandedDistDir;
        }
    }
}
