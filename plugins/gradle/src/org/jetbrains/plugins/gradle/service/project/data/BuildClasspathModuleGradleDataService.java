/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.plugins.gradle.service.project.data;

import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.externalSystem.ExternalSystemManager;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.Key;
import com.intellij.openapi.externalSystem.model.ProjectKeys;
import com.intellij.openapi.externalSystem.model.project.ExternalModuleBuildClasspathPojo;
import com.intellij.openapi.externalSystem.model.project.ExternalProjectBuildClasspathPojo;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
import com.intellij.openapi.externalSystem.model.project.ProjectData;
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider;
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService;
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemLocalSettings;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.externalSystem.util.ExternalSystemConstants;
import com.intellij.openapi.externalSystem.util.Order;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NotNullLazyValue;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.model.data.BuildScriptClasspathData;
import org.jetbrains.plugins.gradle.service.GradleBuildClasspathManager;
import org.jetbrains.plugins.gradle.service.GradleInstallationManager;
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings;
import org.jetbrains.plugins.gradle.settings.GradleSettings;
import org.jetbrains.plugins.gradle.util.GradleConstants;

import java.io.File;
import java.util.*;

/**
 * @author Vladislav.Soroka
 * @since 8/27/13
 */
@Order(ExternalSystemConstants.UNORDERED)
public class BuildClasspathModuleGradleDataService extends AbstractProjectDataService<BuildScriptClasspathData, Module> {

  private static final Logger LOG = Logger.getInstance(BuildClasspathModuleGradleDataService.class);

  @NotNull
  @Override
  public Key<BuildScriptClasspathData> getTargetDataKey() {
    return BuildScriptClasspathData.KEY;
  }

  @Override
  public void importData(@NotNull final Collection<DataNode<BuildScriptClasspathData>> toImport,
                         @Nullable final ProjectData projectData,
                         @NotNull final Project project,
                         @NotNull final IdeModifiableModelsProvider modelsProvider) {
    if (projectData == null || toImport.isEmpty()) {
      return;
    }

    final GradleInstallationManager gradleInstallationManager = ServiceManager.getService(GradleInstallationManager.class);

    ExternalSystemManager<?, ?, ?, ?, ?> manager = ExternalSystemApiUtil.getManager(GradleConstants.SYSTEM_ID);
    assert manager != null;
    AbstractExternalSystemLocalSettings localSettings = manager.getLocalSettingsProvider().fun(project);

    final String linkedExternalProjectPath = projectData.getLinkedExternalProjectPath();
    final NotNullLazyValue<Set<String>> externalProjectGradleSdkLibs = new NotNullLazyValue<Set<String>>() {
      @NotNull
      @Override
      protected Set<String> compute() {
        GradleProjectSettings settings = GradleSettings.getInstance(project).getLinkedProjectSettings(linkedExternalProjectPath);
        if (settings == null || settings.getDistributionType() == null) return Collections.emptySet();

        final Set<String> gradleSdkLibraries = ContainerUtil.newLinkedHashSet();
        File gradleHome =
          gradleInstallationManager.getGradleHome(settings.getDistributionType(), linkedExternalProjectPath, settings.getGradleHome());
        if (gradleHome != null && gradleHome.isDirectory()) {
          final Collection<File> libraries = gradleInstallationManager.getClassRoots(project, linkedExternalProjectPath);
          if (libraries != null) {
            for (File library : libraries) {
              gradleSdkLibraries.add(FileUtil.toCanonicalPath(library.getPath()));
            }
          }
        }
        return gradleSdkLibraries;
      }
    };

    final NotNullLazyValue<Set<String>> buildSrcProjectsRoots = new NotNullLazyValue<Set<String>>() {
      @NotNull
      @Override
      protected Set<String> compute() {
        Set<String> result = new LinkedHashSet<String>();
        //// add main java root of buildSrc project
        result.add(linkedExternalProjectPath + "/buildSrc/src/main/java");
        //// add main groovy root of buildSrc project
        result.add(linkedExternalProjectPath + "/buildSrc/src/main/groovy");
        for (Module module : modelsProvider.getModules(projectData)) {
          final String projectPath = ExternalSystemApiUtil.getExternalProjectPath(module);
          if(projectPath != null && StringUtil.startsWith(projectPath, linkedExternalProjectPath + "/buildSrc")) {
            final List<String> sourceRoots = ContainerUtil.map(modelsProvider.getSourceRoots(module, false), new Function<VirtualFile, String>() {
              @Override
              public String fun(VirtualFile file) {
                return file.getPath();
              }
            });
            result.addAll(sourceRoots);
          }
        }
        return result;
      }
    };

    final Map<String, ExternalProjectBuildClasspathPojo> localProjectBuildClasspath =
      ContainerUtil.newHashMap(localSettings.getProjectBuildClasspath());
    for (final DataNode<BuildScriptClasspathData> node : toImport) {
      if (GradleConstants.SYSTEM_ID.equals(node.getData().getOwner())) {
        DataNode<ModuleData> moduleDataNode = ExternalSystemApiUtil.findParent(node, ProjectKeys.MODULE);
        if (moduleDataNode == null) continue;

        String externalModulePath = moduleDataNode.getData().getLinkedExternalProjectPath();
        GradleProjectSettings settings = GradleSettings.getInstance(project).getLinkedProjectSettings(linkedExternalProjectPath);
        if (settings == null || settings.getDistributionType() == null) {
          LOG.warn("Gradle SDK distribution type was not configured for the project at " + linkedExternalProjectPath);
        }

        final Set<String> buildClasspath = ContainerUtil.newLinkedHashSet();
        BuildScriptClasspathData buildScriptClasspathData = node.getData();
        for (BuildScriptClasspathData.ClasspathEntry classpathEntry : buildScriptClasspathData.getClasspathEntries()) {
          for (String path : classpathEntry.getSourcesFile()) {
            buildClasspath.add(FileUtil.toCanonicalPath(path));
          }

          for (String path : classpathEntry.getClassesFile()) {
            buildClasspath.add(FileUtil.toCanonicalPath(path));
          }
        }

        ExternalProjectBuildClasspathPojo projectBuildClasspathPojo = localProjectBuildClasspath.get(linkedExternalProjectPath);
        if (projectBuildClasspathPojo == null) {
          projectBuildClasspathPojo = new ExternalProjectBuildClasspathPojo(
            moduleDataNode.getData().getExternalName(),
            ContainerUtil.<String>newArrayList(),
            ContainerUtil.<String, ExternalModuleBuildClasspathPojo>newHashMap());
          localProjectBuildClasspath.put(linkedExternalProjectPath, projectBuildClasspathPojo);
        }

        List<String> projectBuildClasspath = ContainerUtil.newArrayList(externalProjectGradleSdkLibs.getValue());
        projectBuildClasspath.addAll(buildSrcProjectsRoots.getValue());

        projectBuildClasspathPojo.setProjectBuildClasspath(projectBuildClasspath);
        projectBuildClasspathPojo.getModulesBuildClasspath().put(
          externalModulePath, new ExternalModuleBuildClasspathPojo(externalModulePath, ContainerUtil.newArrayList(buildClasspath)));
      }
    }
    localSettings.setProjectBuildClasspath(localProjectBuildClasspath);

    if(!project.isDisposed()) {
      GradleBuildClasspathManager.getInstance(project).reload();
    }
  }
}
