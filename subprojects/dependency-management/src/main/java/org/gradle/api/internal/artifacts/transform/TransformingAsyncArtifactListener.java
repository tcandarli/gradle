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

package org.gradle.api.internal.artifacts.transform;

import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.internal.artifacts.configurations.ConfigurationInternal;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet;
import org.gradle.internal.operations.BuildOperationQueue;
import org.gradle.internal.operations.RunnableBuildOperation;

import java.io.File;
import java.util.Map;

class TransformingAsyncArtifactListener implements ResolvedArtifactSet.AsyncArtifactListener {
    private final Map<ComponentArtifactIdentifier, TransformationOperation> artifactResults;
    private final Map<File, TransformationOperation> fileResults;
    private final BuildOperationQueue<RunnableBuildOperation> actions;
    private final ResolvedArtifactSet.AsyncArtifactListener delegate;
    private final Transformation transformation;
    private final ConfigurationInternal configuration;

    TransformingAsyncArtifactListener(Transformation transformation, ResolvedArtifactSet.AsyncArtifactListener delegate, BuildOperationQueue<RunnableBuildOperation> actions, Map<ComponentArtifactIdentifier, TransformationOperation> artifactResults, Map<File, TransformationOperation> fileResults, ConfigurationInternal configuration) {
        this.artifactResults = artifactResults;
        this.actions = actions;
        this.transformation = transformation;
        this.delegate = delegate;
        this.fileResults = fileResults;
        this.configuration = configuration;
    }

    @Override
    public void artifactAvailable(ResolvableArtifact artifact) {
        ComponentArtifactIdentifier artifactId = artifact.getId();
        File file = artifact.getFile();
        TransformationSubject initialSubject = TransformationSubject.initial(artifactId, file, configuration);
        initialSubjectAvailable(artifactId, initialSubject, artifactResults);
    }

    @Override
    public boolean requireArtifactFiles() {
        // Always need the files, as we need to run the transform in order to calculate the output artifacts.
        return true;
    }

    @Override
    public boolean includeFileDependencies() {
        return delegate.includeFileDependencies();
    }

    @Override
    public void fileAvailable(File file) {
        TransformationSubject initialSubject = TransformationSubject.initial(file, configuration);
        initialSubjectAvailable(file, initialSubject, fileResults);
    }

    private <T> void initialSubjectAvailable(T key, TransformationSubject initialSubject, Map<T, TransformationOperation> results) {
        TransformationOperation operation = new TransformationOperation(transformation, initialSubject);
        results.put(key, operation);
        if (transformation.hasCachedResult(initialSubject)) {
            operation.run(null);
        } else {
            actions.add(operation);
        }
    }
}
