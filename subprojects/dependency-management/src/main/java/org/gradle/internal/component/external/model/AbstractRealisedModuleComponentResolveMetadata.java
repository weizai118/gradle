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

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.capabilities.CapabilitiesMetadata;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.internal.DisplayName;
import org.gradle.internal.component.model.ComponentArtifactMetadata;
import org.gradle.internal.component.model.ConfigurationMetadata;
import org.gradle.internal.component.model.ModuleSource;
import org.gradle.internal.component.model.VariantResolveMetadata;

import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class AbstractRealisedModuleComponentResolveMetadata extends AbstractModuleComponentResolveMetadata {

    protected static final String ALL_VARIANTS_NAME = "__ALL||VARIANTS++";

    static ImmutableList<? extends ComponentVariant> enrichVariants(ModuleComponentResolveMetadata mutableMetadata, VariantMetadataRules variantMetadataRules, ImmutableList<? extends ComponentVariant> variants, Set<String> remainingVariants) {
        List<AbstractMutableModuleComponentResolveMetadata.ImmutableVariantImpl> realisedVariants = Lists.newArrayListWithExpectedSize(variants.size());
        for (ComponentVariant variant : variants) {
            remainingVariants.remove(variant.getName());

            ImmutableAttributes attributes = variantMetadataRules.applyVariantAttributeRules(variant, variant.getAttributes());
            realisedVariants.add(new AbstractMutableModuleComponentResolveMetadata.ImmutableVariantImpl(mutableMetadata.getId(), variant.getName(), attributes,
                variant.getDependencies(), variant.getDependencyConstraints(), variant.getFiles(),
                ImmutableCapabilities.of(variant.getCapabilities().getCapabilities())));
        }
        return ImmutableList.copyOf(realisedVariants);
    }

    private final Map<String, ImmutableAttributes> potentialVariantToAttributesMap;
    private final ImmutableAttributes allVariantsAttributes;
    private Optional<ImmutableList<? extends ConfigurationMetadata>> graphVariants;

    public AbstractRealisedModuleComponentResolveMetadata(AbstractRealisedModuleComponentResolveMetadata metadata) {
        super(metadata);
        this.potentialVariantToAttributesMap = metadata.potentialVariantToAttributesMap;
        this.allVariantsAttributes = metadata.allVariantsAttributes;
    }

    public AbstractRealisedModuleComponentResolveMetadata(AbstractRealisedModuleComponentResolveMetadata metadata, ModuleSource source) {
        super(metadata, source);
        this.potentialVariantToAttributesMap = metadata.potentialVariantToAttributesMap;
        this.allVariantsAttributes = metadata.allVariantsAttributes;
    }

    public AbstractRealisedModuleComponentResolveMetadata(AbstractModuleComponentResolveMetadata mutableMetadata, ImmutableList<? extends ComponentVariant> variants, Map<String, ImmutableAttributes> potentialVariantToAttributesMap, ImmutableAttributes allVariantsAttributes) {
        super(mutableMetadata, variants);
        this.potentialVariantToAttributesMap = potentialVariantToAttributesMap;
        this.allVariantsAttributes = allVariantsAttributes;
    }

    @Override
    protected final RealisedConfigurationMetadata createConfiguration(ModuleComponentIdentifier componentId, String name, boolean transitive, boolean visible, ImmutableList<String> hierarchy) {
        return createConfiguration(componentId, name, transitive, visible, hierarchy, potentialVariantToAttributesMap, allVariantsAttributes);
    }

    protected abstract RealisedConfigurationMetadata createConfiguration(ModuleComponentIdentifier componentId, String name, boolean transitive, boolean visible, ImmutableList<String> hierarchy, Map<String, ImmutableAttributes> potentialVariantToAttributesMap, ImmutableAttributes allVariantsAttributes);

    @Override
    public Optional<ImmutableList<? extends ConfigurationMetadata>> getVariantsForGraphTraversal() {
        if (graphVariants == null) {
            graphVariants = buildVariantsForGraphTraversal(getVariants());
        }
        return graphVariants;
    }

    protected Map<String, ImmutableAttributes> getPotentialVariantToAttributesMap() {
        return potentialVariantToAttributesMap;
    }

    protected ImmutableAttributes getAllVariantsAttributes() {
        return allVariantsAttributes;
    }

    private Optional<ImmutableList<? extends ConfigurationMetadata>> buildVariantsForGraphTraversal(List<? extends ComponentVariant> variants) {
        if (variants.isEmpty()) {
            return maybeDeriveVariants();
        }
        ImmutableList.Builder<ConfigurationMetadata> configurations = new ImmutableList.Builder<ConfigurationMetadata>();
        for (ComponentVariant variant : variants) {
            configurations.add(new RealisedVariantBackedConfigurationMetadata(getId(), variant, getAttributes(), getAttributesFactory()));
        }
        return Optional.<ImmutableList<? extends ConfigurationMetadata>>of(configurations.build());
    }

    protected static class NameOnlyVariantResolveMetadata implements VariantResolveMetadata {
        private final String name;

        public NameOnlyVariantResolveMetadata(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public DisplayName asDescribable() {
            throw new UnsupportedOperationException("NameOnlyVariantResolveMetadata cannot be used that way");
        }

        @Override
        public AttributeContainerInternal getAttributes() {
            throw new UnsupportedOperationException("NameOnlyVariantResolveMetadata cannot be used that way");
        }

        @Override
        public List<? extends ComponentArtifactMetadata> getArtifacts() {
            throw new UnsupportedOperationException("NameOnlyVariantResolveMetadata cannot be used that way");
        }

        @Override
        public CapabilitiesMetadata getCapabilities() {
            throw new UnsupportedOperationException("NameOnlyVariantResolveMetadata cannot be used that way");
        }
    }
}
