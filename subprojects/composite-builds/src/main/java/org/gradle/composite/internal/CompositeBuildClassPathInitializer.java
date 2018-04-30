/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.composite.internal;

import org.gradle.api.Task;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.internal.initialization.ScriptClassPathInitializer;
import org.gradle.api.internal.tasks.CrossBuildTaskReference;
import org.gradle.api.internal.tasks.TaskDependencyInternal;
import org.gradle.api.internal.tasks.TaskDependencyResolveContext;
import org.gradle.initialization.BuildIdentity;

import java.util.LinkedList;
import java.util.List;

public class CompositeBuildClassPathInitializer implements ScriptClassPathInitializer {
    private final IncludedBuildTaskGraph includedBuildTaskGraph;
    private final BuildIdentifier currentBuild;

    public CompositeBuildClassPathInitializer(IncludedBuildTaskGraph includedBuildTaskGraph, BuildIdentity buildIdentity) {
        this.includedBuildTaskGraph = includedBuildTaskGraph;
        this.currentBuild = buildIdentity.getCurrentBuild();
    }

    @Override
    public void execute(Configuration classpath) {
        ArtifactCollection artifacts = classpath.getIncoming().getArtifacts();
        TaskDependencyInternal buildDependencies = (TaskDependencyInternal) artifacts.getArtifactFiles().getBuildDependencies();

        // Queue all of the tasks required to build the classpath
        // This should use exactly the same code that is used to build the task graphs for the main build, rather than reimplement it here, differently
        final List<CrossBuildTaskReference> queued = new LinkedList<CrossBuildTaskReference>();
        buildDependencies.visitDependencies(new TaskDependencyResolveContext() {
            @Override
            public void add(Object dependency) {
                if (dependency instanceof TaskDependencyInternal) {
                    ((TaskDependencyInternal)dependency).visitDependencies(this);
                    return;
                }
                if (!(dependency instanceof CrossBuildTaskReference)) {
                    throw new UnsupportedOperationException("Task dependency " + dependency + " not supported in build script classpath");
                }
                CrossBuildTaskReference reference = (CrossBuildTaskReference) dependency;
                if (reference.getBuildIdentifier().equals(currentBuild)) {
                    throw new UnsupportedOperationException("Build script classpath should not contain references to consuming build.");
                }
                includedBuildTaskGraph.addTask(currentBuild, reference.getBuildIdentifier(), reference.getTaskPath());
                queued.add(reference);
           }

            @Override
            public Task getTask() {
                return null;
            }
        });
        // Run the queued tasks
        for (CrossBuildTaskReference reference : queued) {
            includedBuildTaskGraph.awaitCompletion(reference.getBuildIdentifier(), reference.getTaskPath());
        }
    }
}
