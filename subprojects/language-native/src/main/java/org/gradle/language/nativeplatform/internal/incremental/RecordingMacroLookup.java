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

package org.gradle.language.nativeplatform.internal.incremental;

import com.google.common.collect.Iterators;
import org.gradle.language.nativeplatform.internal.Macro;
import org.gradle.language.nativeplatform.internal.MacroFunction;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

class RecordingMacroLookup implements MacroLookup {
    private final MacroLookup visibleMacros;
    private final Map<String, Set<Macro>> observedMacros;
    private final Map<String, Set<MacroFunction>> observedMacroFunctions;

    RecordingMacroLookup(MacroLookup visibleMacros) {
        this.visibleMacros = visibleMacros;
        observedMacros = new HashMap<String, Set<Macro>>();
        observedMacroFunctions = new HashMap<String, Set<MacroFunction>>();
    }

    public Map<String, Set<Macro>> getObservedMacros() {
        return observedMacros;
    }

    public Map<String, Set<MacroFunction>> getObservedMacroFunctions() {
        return observedMacroFunctions;
    }

    @Override
    public Iterator<Macro> getMacros(String name) {
        Set<Macro> macros = observedMacros.get(name);
        if (macros == null) {
            macros = new LinkedHashSet<Macro>();
            Iterators.addAll(macros, visibleMacros.getMacros(name));
            observedMacros.put(name, macros);
        }
        return macros.iterator();
    }

    @Override
    public Iterator<MacroFunction> getMacroFunctions(String name) {
        Set<MacroFunction> macroFunctions = observedMacroFunctions.get(name);
        if (macroFunctions == null) {
            macroFunctions = new LinkedHashSet<MacroFunction>();
            Iterators.addAll(macroFunctions, visibleMacros.getMacroFunctions(name));
            observedMacroFunctions.put(name, macroFunctions);
        }
        return macroFunctions.iterator();
    }
}
