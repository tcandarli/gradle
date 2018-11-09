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

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.artifacts.transform.ArtifactTransform;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.InstantiatorFactory;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.isolation.Isolatable;
import org.gradle.internal.service.DefaultServiceRegistry;

import java.io.File;
import java.util.List;
import java.util.function.Supplier;
import javax.annotation.Nullable;

public class DefaultTransformer implements Transformer {

    private final Class<? extends ArtifactTransform> implementationClass;
    private final Isolatable<Object[]> parameters;
    private final InstantiatorFactory instantiator;
    private final HashCode inputsHash;

    public DefaultTransformer(Class<? extends ArtifactTransform> implementationClass, Isolatable<Object[]> parameters, HashCode inputsHash, InstantiatorFactory instantiator) {
        this.implementationClass = implementationClass;
        this.parameters = parameters;
        this.instantiator = instantiator;
        this.inputsHash = inputsHash;
    }

    @Override
    public List<File> transform(File primaryInput, File outputDir, Supplier<FileCollection> artifactDependencies) {
        ArtifactTransform transformer = newTransformer(artifactDependencies);
        transformer.setOutputDirectory(outputDir);
        List<File> outputs = transformer.transform(primaryInput);
        return validateOutputs(primaryInput, outputDir, outputs);
    }

    private List<File> validateOutputs(File primaryInput, File outputDir, @Nullable List<File> outputs) {
        if (outputs == null) {
            throw new InvalidUserDataException("Transform returned null result.");
        }
        String inputFilePrefix = primaryInput.getPath() + File.separator;
        String outputDirPrefix = outputDir.getPath() + File.separator;
        for (File output : outputs) {
            if (!output.exists()) {
                throw new InvalidUserDataException("Transform output file " + output.getPath() + " does not exist.");
            }
            if (output.equals(primaryInput) || output.equals(outputDir)) {
                continue;
            }
            if (output.getPath().startsWith(outputDirPrefix)) {
                continue;
            }
            if (output.getPath().startsWith(inputFilePrefix)) {
                continue;
            }
            throw new InvalidUserDataException("Transform output file " + output.getPath() + " is not a child of the transform's input file or output directory.");
        }
        return outputs;
    }

    private ArtifactTransform newTransformer(Supplier<FileCollection> artifactDependencies) {
        DefaultServiceRegistry registry = new DefaultServiceRegistry();
        registry.addProvider(new Object() {
            FileCollection createArtifactDependencies() {
                return artifactDependencies.get();
            }
        });
        return instantiator.inject(registry).newInstance(implementationClass, parameters.isolate());
    }

    @Override
    public HashCode getSecondaryInputHash() {
        return inputsHash;
    }

    @Override
    public Class<? extends ArtifactTransform> getImplementationClass() {
        return implementationClass;
    }

    @Override
    public String getDisplayName() {
        return implementationClass.getSimpleName();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DefaultTransformer that = (DefaultTransformer) o;

        return inputsHash.equals(that.inputsHash);
    }

    @Override
    public int hashCode() {
        return inputsHash.hashCode();
    }
}
