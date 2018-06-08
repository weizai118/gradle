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

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.Usage;
import org.gradle.api.internal.attributes.AttributesSchemaInternal;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.changedetection.state.CoercingStringValueSnapshot;
import org.gradle.api.internal.model.NamedObjectInstantiator;
import org.gradle.internal.component.external.descriptor.MavenScope;
import org.gradle.internal.component.model.ConfigurationMetadata;
import org.gradle.internal.component.model.DefaultIvyArtifactName;
import org.gradle.internal.component.model.ExcludeMetadata;
import org.gradle.internal.component.model.IvyArtifactName;
import org.gradle.internal.component.model.ModuleSource;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class RealisedMavenModuleResolveMetadata extends AbstractRealisedModuleComponentResolveMetadata implements MavenModuleResolveMetadata {

    public static final String POM_PACKAGING = "pom";
    public static final Collection<String> JAR_PACKAGINGS = Arrays.asList("jar", "ejb", "bundle", "maven-plugin", "eclipse-plugin");
    private static final PreferJavaRuntimeVariant SCHEMA_DEFAULT_JAVA_VARIANTS = PreferJavaRuntimeVariant.schema();
    // We need to work with the 'String' version of the usage attribute, since this is expected for all providers by the `PreferJavaRuntimeVariant` schema
    private static final Attribute<String> USAGE_ATTRIBUTE = Attribute.of(Usage.USAGE_ATTRIBUTE.getName(), String.class);

    public static RealisedMavenModuleResolveMetadata transform(DefaultMavenModuleResolveMetadata metadata) {
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
        return new RealisedMavenModuleResolveMetadata(metadata, variants, potentialVariantToAttributesMap, allVariantsAttributes);
    }

    private final boolean improvedPomSupportEnabled;
    private final NamedObjectInstantiator objectInstantiator;

    private final ImmutableList<MavenDependencyDescriptor> dependencies;
    private final String packaging;
    private final boolean relocated;
    private final String snapshotTimestamp;

    private ImmutableList<? extends ConfigurationMetadata> derivedVariants;

    public RealisedMavenModuleResolveMetadata(DefaultMavenModuleResolveMetadata metadata, ImmutableList<? extends ComponentVariant> variants, Map<String, ImmutableAttributes> potentialVariantToAttributesMap, ImmutableAttributes allVariantsAttributes) {
        super(metadata, variants, potentialVariantToAttributesMap, allVariantsAttributes);
        this.improvedPomSupportEnabled = metadata.isImprovedPomSupportEnabled();
        this.objectInstantiator = metadata.getObjectInstantiator();
        packaging = metadata.getPackaging();
        relocated = metadata.isRelocated();
        snapshotTimestamp = metadata.getSnapshotTimestamp();
        dependencies = metadata.getDependencies();
    }

    private RealisedMavenModuleResolveMetadata(RealisedMavenModuleResolveMetadata metadata, ModuleSource source) {
        super(metadata, source);
        this.improvedPomSupportEnabled = metadata.improvedPomSupportEnabled;
        this.objectInstantiator = metadata.objectInstantiator;
        packaging = metadata.packaging;
        relocated = metadata.relocated;
        snapshotTimestamp = metadata.snapshotTimestamp;
        dependencies = metadata.dependencies;

        copyCachedState(metadata);
    }

    @Override
    protected RealisedConfigurationMetadata createConfiguration(ModuleComponentIdentifier componentId, String name, boolean transitive, boolean visible, ImmutableList<String> hierarchy, Map<String, ImmutableAttributes> potentialVariantToAttributesMap, ImmutableAttributes allVariantsAttributes) {
        ImmutableList<? extends ModuleComponentArtifactMetadata> artifacts = getArtifactsForConfiguration(name);
        RealisedConfigurationMetadata configuration = new RealisedConfigurationMetadata(componentId, name, transitive, visible, hierarchy, artifacts, ImmutableList.<ExcludeMetadata>of(), getAttributes().asImmutable());
        configuration.setDependencies(filterDependencies(configuration));
        return configuration;
    }

    @Override
    protected Optional<ImmutableList<? extends ConfigurationMetadata>> maybeDeriveVariants() {
        return isJavaLibrary() ? Optional.<ImmutableList<? extends ConfigurationMetadata>>of(getDerivedVariants()) : Optional.<ImmutableList<? extends ConfigurationMetadata>>absent();
    }

    private ImmutableList<? extends ConfigurationMetadata> getDerivedVariants() {
        if (derivedVariants == null) {
            ImmutableAttributes additionalCompileAttributes = getPotentialVariantToAttributesMap().get("compile");
            if (additionalCompileAttributes == null) {
                additionalCompileAttributes = getAllVariantsAttributes();
            }
            ImmutableAttributes additionalRuntimeAttributes = getPotentialVariantToAttributesMap().get("runtime");
            if (additionalCompileAttributes == null) {
                additionalCompileAttributes = getAllVariantsAttributes();
            }
            derivedVariants = ImmutableList.of(
                withUsageAttribute((DefaultConfigurationMetadata) getConfiguration("compile"), Usage.JAVA_API, getAttributesFactory(), additionalCompileAttributes),
                withUsageAttribute((DefaultConfigurationMetadata) getConfiguration("runtime"), Usage.JAVA_RUNTIME, getAttributesFactory(), additionalRuntimeAttributes));
        }
        return derivedVariants;
    }

    private ConfigurationMetadata withUsageAttribute(DefaultConfigurationMetadata conf, String usage, ImmutableAttributesFactory attributesFactory, ImmutableAttributes additionalAttributes) {
        ImmutableAttributes attributes = attributesFactory.concat(getAttributes().asImmutable(), USAGE_ATTRIBUTE, new CoercingStringValueSnapshot(usage, objectInstantiator));
        return conf.withAttributes(attributesFactory.concat(attributes, additionalAttributes));
    }

    private ImmutableList<? extends ModuleComponentArtifactMetadata> getArtifactsForConfiguration(String name) {
        ImmutableList<? extends ModuleComponentArtifactMetadata> artifacts;
        if (name.equals("compile") || name.equals("runtime") || name.equals("default") || name.equals("test")) {
            artifacts = ImmutableList.of(new DefaultModuleComponentArtifactMetadata(getId(), new DefaultIvyArtifactName(getId().getModule(), "jar", "jar")));
        } else {
            artifacts = ImmutableList.of();
        }
        return artifacts;
    }

    private ImmutableList<ModuleDependencyMetadata> filterDependencies(ConfigurationMetadata config) {
        ImmutableList.Builder<ModuleDependencyMetadata> filteredDependencies = ImmutableList.builder();
        boolean isOptionalConfiguration = "optional".equals(config.getName());

        for (MavenDependencyDescriptor dependency : dependencies) {
            if (isOptionalConfiguration && includeInOptionalConfiguration(dependency)) {
                filteredDependencies.add(new OptionalConfigurationDependencyMetadata(config, getId(), dependency));
            } else if (include(dependency, config.getHierarchy())) {
                filteredDependencies.add(contextualize(config, getId(), dependency));
            }
        }
        return filteredDependencies.build();
    }

    private ModuleDependencyMetadata contextualize(ConfigurationMetadata config, ModuleComponentIdentifier componentId, MavenDependencyDescriptor incoming) {
        ConfigurationBoundExternalDependencyMetadata dependency = new ConfigurationBoundExternalDependencyMetadata(config, componentId, incoming);
        if (improvedPomSupportEnabled) {
            dependency.alwaysUseAttributeMatching();
        }
        return dependency;
    }

    private boolean includeInOptionalConfiguration(MavenDependencyDescriptor dependency) {
        MavenScope dependencyScope = dependency.getScope();
        // Include all 'optional' dependencies in "optional" configuration
        return dependency.isOptional()
            && dependencyScope != MavenScope.Test
            && dependencyScope != MavenScope.System;
    }

    private boolean include(MavenDependencyDescriptor dependency, Collection<String> hierarchy) {
        MavenScope dependencyScope = dependency.getScope();
        if (dependency.isOptional() && ignoreOptionalDependencies()) {
            return false;
        }
        return hierarchy.contains(dependencyScope.getLowerName());
    }

    private boolean ignoreOptionalDependencies() {
        return !improvedPomSupportEnabled;
    }

    @Override
    public RealisedMavenModuleResolveMetadata withSource(ModuleSource source) {
        return new RealisedMavenModuleResolveMetadata(this, source);
    }

    @Override
    public MutableMavenModuleResolveMetadata asMutable() {
        throw new UnsupportedOperationException("Implement me!");
    }

    public String getPackaging() {
        return packaging;
    }

    public boolean isRelocated() {
        return relocated;
    }

    public boolean isPomPackaging() {
        return POM_PACKAGING.equals(packaging);
    }

    public boolean isKnownJarPackaging() {
        return JAR_PACKAGINGS.contains(packaging);
    }

    private boolean isJavaLibrary() {
        return improvedPomSupportEnabled && (isKnownJarPackaging() || isPomPackaging());
    }

    @Nullable
    public String getSnapshotTimestamp() {
        return snapshotTimestamp;
    }

    @Nullable
    @Override
    public AttributesSchemaInternal getAttributesSchema() {
        return SCHEMA_DEFAULT_JAVA_VARIANTS;
    }

    @Override
    public ImmutableList<MavenDependencyDescriptor> getDependencies() {
        return dependencies;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        RealisedMavenModuleResolveMetadata that = (RealisedMavenModuleResolveMetadata) o;
        return relocated == that.relocated
            && Objects.equal(dependencies, that.dependencies)
            && Objects.equal(packaging, that.packaging)
            && Objects.equal(snapshotTimestamp, that.snapshotTimestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(),
            dependencies,
            packaging,
            relocated,
            snapshotTimestamp);
    }

    /**
     * Adapts a MavenDependencyDescriptor to `DependencyMetadata` for the magic "optional" configuration.
     *
     * This configuration has special semantics:
     *  - Dependencies in the "optional" configuration are _never_ themselves optional (ie not 'pending')
     *  - Dependencies in the "optional" configuration can have dependency artifacts, even if the dependency is flagged as 'optional'.
     *    (For a standard configuration, any dependency flagged as 'optional' will have no dependency artifacts).
     */
    private static class OptionalConfigurationDependencyMetadata extends ConfigurationBoundExternalDependencyMetadata {
        private final MavenDependencyDescriptor dependencyDescriptor;

        public OptionalConfigurationDependencyMetadata(ConfigurationMetadata configuration, ModuleComponentIdentifier componentId, MavenDependencyDescriptor delegate) {
            super(configuration, componentId, delegate);
            this.dependencyDescriptor = delegate;
        }

        /**
         * Dependencies markes as optional/pending in the "optional" configuration _can_ have dependency artifacts.
         */
        @Override
        public List<IvyArtifactName> getArtifacts() {
            IvyArtifactName dependencyArtifact = dependencyDescriptor.getDependencyArtifact();
            return dependencyArtifact == null ? ImmutableList.<IvyArtifactName>of() : ImmutableList.of(dependencyArtifact);
        }

        /**
         * Dependencies in the "optional" configuration are never 'pending'.
         */
        @Override
        public boolean isPending() {
            return false;
        }
    }
}
