/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.internal.component.external.model;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.internal.artifacts.ivyservice.NamespaceId;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.internal.component.external.descriptor.Artifact;
import org.gradle.internal.component.external.descriptor.Configuration;
import org.gradle.internal.component.model.Exclude;
import org.gradle.internal.component.model.ExcludeMetadata;
import org.gradle.internal.component.model.ModuleSource;
import org.gradle.util.CollectionUtils;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class RealisedIvyModuleResolveMetadata extends AbstractRealisedModuleComponentResolveMetadata implements IvyModuleResolveMetadata {

    public static RealisedIvyModuleResolveMetadata transform(DefaultIvyModuleResolveMetadata metadata) {
        VariantMetadataRules variantMetadataRules = metadata.getVariantMetadataRules();
        HashSet<String> remainingVariants = Sets.newHashSet(variantMetadataRules.getSeenVariants());
        boolean allVariantsUsed = variantMetadataRules.isSeenAllVariants();
        ImmutableList<? extends ComponentVariant> variants = enrichVariants(metadata, variantMetadataRules, metadata.getVariants(), remainingVariants);
        Map<String, ImmutableAttributes> potentialVariantToAttributesMap = Maps.newHashMapWithExpectedSize(remainingVariants.size());
        for (String variant : remainingVariants) {
            ImmutableAttributes variantAttributes = variantMetadataRules.applyVariantAttributeRules(new NameOnlyVariantResolveMetadata(variant), ImmutableAttributes.EMPTY);
            potentialVariantToAttributesMap.put(variant, variantAttributes);
        }
        ImmutableAttributes allVariantsAttributes;
        if (allVariantsUsed) {
            allVariantsAttributes = variantMetadataRules.applyVariantAttributeRules(new NameOnlyVariantResolveMetadata(ALL_VARIANTS_NAME), ImmutableAttributes.EMPTY);
        } else {
            allVariantsAttributes = ImmutableAttributes.EMPTY;
        }
        return new RealisedIvyModuleResolveMetadata(metadata, variants, potentialVariantToAttributesMap, allVariantsAttributes);
    }

    private final ImmutableMap<String, Configuration> configurationDefinitions;
    private final ImmutableList<IvyDependencyDescriptor> dependencies;
    private final ImmutableList<Artifact> artifactDefinitions;
    private final ImmutableList<Exclude> excludes;
    private final ImmutableMap<NamespaceId, String> extraAttributes;
    private final String branch;
    private Map<Artifact, ModuleComponentArtifactMetadata> artifacts;

    public RealisedIvyModuleResolveMetadata(RealisedIvyModuleResolveMetadata metadata, List<IvyDependencyDescriptor> dependencies) {
        super(metadata);
        this.configurationDefinitions = metadata.getConfigurationDefinitions();
        this.branch = metadata.getBranch();
        this.artifactDefinitions = metadata.getArtifactDefinitions();
        this.dependencies = ImmutableList.copyOf(dependencies);
        this.excludes = metadata.getExcludes();
        this.extraAttributes = metadata.getExtraAttributes();
    }

    public RealisedIvyModuleResolveMetadata(RealisedIvyModuleResolveMetadata metadata, ModuleSource source) {
        super(metadata, source);
        this.configurationDefinitions = metadata.configurationDefinitions;
        this.branch = metadata.branch;
        this.artifactDefinitions = metadata.artifactDefinitions;
        this.dependencies = metadata.dependencies;
        this.excludes = metadata.excludes;
        this.extraAttributes = metadata.extraAttributes;

        copyCachedState(metadata);
    }

    public RealisedIvyModuleResolveMetadata(DefaultIvyModuleResolveMetadata mutableMetadata, ImmutableList<? extends ComponentVariant> variants, Map<String, ImmutableAttributes> potentialVariantToAttributesMap, ImmutableAttributes allVariantsAttributes) {
        super(mutableMetadata, variants, potentialVariantToAttributesMap, allVariantsAttributes);
        this.configurationDefinitions = mutableMetadata.getConfigurationDefinitions();
        this.branch = mutableMetadata.getBranch();
        this.artifactDefinitions = mutableMetadata.getArtifactDefinitions();
        this.dependencies = mutableMetadata.getDependencies();
        this.excludes = mutableMetadata.getExcludes();
        this.extraAttributes = mutableMetadata.getExtraAttributes();
    }

    @Override
    protected RealisedConfigurationMetadata createConfiguration(ModuleComponentIdentifier componentId, String name, boolean transitive, boolean visible, ImmutableList<String> hierarchy, Map<String, ImmutableAttributes> potentialVariantToAttributesMap, ImmutableAttributes allVariantsAttributes) {
        if (artifacts == null) {
            artifacts = new IdentityHashMap<Artifact, ModuleComponentArtifactMetadata>();
        }
        IvyConfigurationHelper configurationHelper = new IvyConfigurationHelper(artifactDefinitions, artifacts, excludes, dependencies, componentId);
        ImmutableList<ModuleComponentArtifactMetadata> artifacts = configurationHelper.filterArtifacts(name, hierarchy);
        ImmutableList<ExcludeMetadata> excludesForConfiguration = configurationHelper.filterExcludes(hierarchy);

        RealisedConfigurationMetadata configuration = new RealisedConfigurationMetadata(componentId, name, transitive, visible, hierarchy, ImmutableList.copyOf(artifacts), excludesForConfiguration, getAttributes().asImmutable());
        configuration.setDependencies(configurationHelper.filterDependencies(configuration));
        return configuration;
    }

    @Override
    public MutableIvyModuleResolveMetadata asMutable() {
        throw new UnsupportedOperationException("Implement me!");
    }

    @Override
    public IvyModuleResolveMetadata withSource(ModuleSource source) {
        return new RealisedIvyModuleResolveMetadata(this, source);
    }

    @Nullable
    @Override
    public String getBranch() {
        return branch;
    }

    @Override
    public ImmutableMap<String, Configuration> getConfigurationDefinitions() {
        return configurationDefinitions;
    }

    @Override
    public ImmutableList<Artifact> getArtifactDefinitions() {
        return artifactDefinitions;
    }

    @Override
    public ImmutableList<Exclude> getExcludes() {
        return excludes;
    }

    @Override
    public ImmutableMap<NamespaceId, String> getExtraAttributes() {
        return extraAttributes;
    }

    @Override
    public IvyModuleResolveMetadata withDynamicConstraintVersions() {
        List<IvyDependencyDescriptor> transformed = CollectionUtils.collect(getDependencies(), new Transformer<IvyDependencyDescriptor, IvyDependencyDescriptor>() {
            @Override
            public IvyDependencyDescriptor transform(IvyDependencyDescriptor dependency) {
                ModuleComponentSelector selector = dependency.getSelector();
                String dynamicConstraintVersion = dependency.getDynamicConstraintVersion();
                ModuleComponentSelector newSelector = DefaultModuleComponentSelector.newSelector(selector.getGroup(), selector.getModule(), dynamicConstraintVersion);
                return dependency.withRequested(newSelector);
            }
        });
        return this.withDependencies(transformed);
    }

    @Override
    public ImmutableList<IvyDependencyDescriptor> getDependencies() {
        return dependencies;
    }

    private IvyModuleResolveMetadata withDependencies(List<IvyDependencyDescriptor> transformed) {
        return new RealisedIvyModuleResolveMetadata(this, transformed);
    }

}
