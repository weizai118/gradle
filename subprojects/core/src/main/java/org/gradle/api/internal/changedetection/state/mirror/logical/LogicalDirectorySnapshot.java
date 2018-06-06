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

package org.gradle.api.internal.changedetection.state.mirror.logical;

import java.util.HashMap;
import java.util.Map;

public class LogicalDirectorySnapshot implements LogicalSnapshot {
    private final String name;
    private Map<String, LogicalSnapshot> children = new HashMap<String, LogicalSnapshot>();

    public LogicalDirectorySnapshot(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean accept(LogicalSnapshotVisitor visitor) {
        if (!visitor.visitEnter(this)) {
            return false;
        }
        for (LogicalSnapshot logicalSnapshot : children.values()) {
            if (!logicalSnapshot.accept(visitor)) {
                break;
            }
        }
        return visitor.visitLeave(this);
    }

    public Map<String, LogicalSnapshot> getChildren() {
        return children;
    }
}
