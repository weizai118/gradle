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

package org.gradle.language.nativeplatform.internal.incremental;

import com.google.common.collect.AbstractIterator;
import org.gradle.language.nativeplatform.internal.IncludeDirectives;
import org.gradle.language.nativeplatform.internal.Macro;
import org.gradle.language.nativeplatform.internal.MacroFunction;

import java.io.File;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class DefaultCollectingMacroLookup implements CollectingMacroLookup {
    private final Map<File, IncludeDirectives> visible = new LinkedHashMap<File, IncludeDirectives>();

    @Override
    public void append(File file, IncludeDirectives includeDirectives) {
        if (!includeDirectives.hasMacros() && !includeDirectives.hasMacroFunctions()) {
            // Ignore
            return;
        }
        if (!visible.containsKey(file)) {
            visible.put(file, includeDirectives);
        }
    }

    @Override
    public void append(MacroSource source) {
        source.collectInto(this);
    }

    @Override
    public Iterator<Macro> getMacros(final String name) {
        if (visible.isEmpty()) {
            return Collections.emptyIterator();
        }
        return new AbstractIterator<Macro>() {
            Iterator<IncludeDirectives> files = visible.values().iterator();
            Iterator<Macro> current = Collections.emptyIterator();

            @Override
            protected Macro computeNext() {
                while (!current.hasNext() && files.hasNext()) {
                    current = files.next().getMacros(name).iterator();
                }
                if (!current.hasNext()) {
                    return endOfData();
                }
                return current.next();
            }
        };
    }

    @Override
    public Iterator<MacroFunction> getMacroFunctions(final String name) {
        if (visible.isEmpty()) {
            return Collections.emptyIterator();
        }
        return new AbstractIterator<MacroFunction>() {
            Iterator<IncludeDirectives> files = visible.values().iterator();
            Iterator<MacroFunction> current = Collections.emptyIterator();

            @Override
            protected MacroFunction computeNext() {
                while (!current.hasNext() && files.hasNext()) {
                    current = files.next().getMacroFunctions(name).iterator();
                }
                if (!current.hasNext()) {
                    return endOfData();
                }
                return current.next();
            }
        };
    }

    @Override
    public void appendTo(CollectingMacroLookup lookup) {
        for (Map.Entry<File, IncludeDirectives> entry : visible.entrySet()) {
            lookup.append(entry.getKey(), entry.getValue());
        }
    }
}
