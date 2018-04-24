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

package org.gradle.language.nativeplatform.internal.incremental

import org.gradle.language.nativeplatform.internal.IncludeDirectives
import org.gradle.language.nativeplatform.internal.Macro
import org.gradle.language.nativeplatform.internal.MacroFunction
import spock.lang.Specification


class DefaultCollectingMacroLookupTest extends Specification {
    def "does not contain any macros when empty"() {
        def macros = new DefaultCollectingMacroLookup()

        expect:
        !macros.getMacros("m").hasNext()
    }

    def "does not contain any macro functions when empty"() {
        def macros = new DefaultCollectingMacroLookup()

        expect:
        !macros.getMacroFunctions("m").hasNext()
    }

    def "can iterate macros from source files"() {
        def file1 = Stub(IncludeDirectives)
        def file2 = Stub(IncludeDirectives)
        def file3 = Stub(IncludeDirectives)
        def m1 = Stub(Macro)
        def m2 = Stub(Macro)
        def m3 = Stub(Macro)

        file1.hasMacros() >> true
        file2.hasMacros() >> true
        file3.hasMacros() >> true
        file1.getMacros("m") >> [m1, m2]
        file2.getMacros("m") >> []
        file3.getMacros("m") >> [m3]

        given:
        def macros = new DefaultCollectingMacroLookup()
        macros.append(new File("f1"), file1)
        macros.append(new File("f2"), file2)
        macros.append(new File("f3"), file3)

        expect:
        macros.getMacros("m") as List == [m1, m2, m3]
    }

    def "can iterate macro functions from source files"() {
        def file1 = Stub(IncludeDirectives)
        def file2 = Stub(IncludeDirectives)
        def file3 = Stub(IncludeDirectives)
        def m1 = Stub(MacroFunction)
        def m2 = Stub(MacroFunction)
        def m3 = Stub(MacroFunction)

        file1.hasMacroFunctions() >> true
        file2.hasMacroFunctions() >> true
        file3.hasMacroFunctions() >> true
        file1.getMacroFunctions("m") >> [m1, m2]
        file2.getMacroFunctions("m") >> []
        file3.getMacroFunctions("m") >> [m3]

        given:
        def macros = new DefaultCollectingMacroLookup()
        macros.append(new File("f1"), file1)
        macros.append(new File("f2"), file2)
        macros.append(new File("f3"), file3)

        expect:
        macros.getMacroFunctions("m") as List == [m1, m2, m3]
    }
}
