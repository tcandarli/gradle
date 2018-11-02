/*
 * Copyright 2016 the original author or authors.
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
package org.gradle.testing.performance.generator.tasks


import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import org.gradle.api.DefaultTask
import org.gradle.api.InvalidUserDataException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.internal.os.OperatingSystem

/**
 * Checkout a project template from a git repository.
 *
 * In git terms, you can target a <em>branch</em>, a <em>commit</em> or a <em>tag</em>.
 * This task distinguishes <em>branch</em> and other git references usage as a mean to optimize the cloning/checkout
 * process.
 *
 * When targeting a <em>branch</em>, set the {@link #branch} property to the <em>branch</em> name.
 * The task will then do a shallow clone at the specified branch in a single step.
 *
 * When targeting a <em>commit</em> or a <em>tag</em>, set the {@link #ref} property accordingly.
 * The task will then do a full clone and then checkout the specified git reference.
 */
class CheckoutRemoteProjectTask extends DefaultTask {

    /**
     * URI of the git repository.
     *
     * Either the remote git repository URL, the path to a local bare git repository or the path to a local git clone.
     */
    @Input
    String remoteUri

    /**
     * Git branch to use.
     *
     * If unset, {@link #ref} must be set.
     */
    @Input
    @Optional
    String branch

    /**
     * Git reference to use.
     *
     * If unset, {@link #branch} must be set.
     */
    @Input
    @Optional
    Property<String> ref = project.objects.property(String)

    protected File checkout() {
        validateInputs()

        File checkoutDir = cleanTemporaryDir()

        if (branch) {
            checkoutBranch(remoteUri, branch, checkoutDir)
        } else {
            checkoutRef(remoteUri, ref.get(), checkoutDir)
        }

        return checkoutDir
    }

    private void validateInputs() {
        if (!branch && !ref.isPresent()) {
            throw new InvalidUserDataException("Either ${name}.branch or ${name}.ref must be set")
        }
        if (branch && ref.isPresent() && branch != ref.get()) {
            throw new InvalidUserDataException(
                "Both ${name}.branch and ${name}.ref cannot have different values, " +
                    "respectively '$branch' and '${ref.get()}'")
        }
    }

    private File cleanTemporaryDir() {
        File tmpDir = getTemporaryDir()
        if (tmpDir.exists()) {
            project.delete(tmpDir)
        }
        return tmpDir
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private void checkoutBranch(String remoteUri, String branch, File tmpDir) {
        project.exec {
            commandLine = ["git", "clone", "--depth", "1", "--branch", branch, remoteUri, tmpDir.absolutePath]
            if (OperatingSystem.current().windows) {
                commandLine = ["cmd", "/c"] + commandLine
            }
            errorOutput = System.out
        }
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private void checkoutRef(String remoteUri, String ref, File tmpDir) {
        project.exec {
            commandLine = ["git", "clone", remoteUri, tmpDir.absolutePath]
            errorOutput = System.out
        }
        project.exec {
            commandLine = ["git", "checkout", ref]
            workingDir = tmpDir
            errorOutput = System.out
        }
    }
}
