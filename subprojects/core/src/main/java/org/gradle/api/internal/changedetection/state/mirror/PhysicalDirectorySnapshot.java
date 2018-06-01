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

package org.gradle.api.internal.changedetection.state.mirror;

import com.google.common.base.Preconditions;
import org.gradle.api.internal.changedetection.state.DirContentSnapshot;
import org.gradle.internal.Cast;

import java.nio.file.Path;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SuppressWarnings("Since15")
public class PhysicalDirectorySnapshot implements PhysicalSnapshot {
    private final ConcurrentMap<String, PhysicalSnapshot> children = new ConcurrentHashMap<String, PhysicalSnapshot>();
    private final String name;
    private final Path path;

    public PhysicalDirectorySnapshot(Path path, String name) {
        this.path = path;
        this.name = name;
    }

    @Override
    public PhysicalSnapshot find(String[] segments, int offset) {
        if (segments.length == offset) {
            return this;
        }
        PhysicalSnapshot child = children.get(segments[offset]);
        return child != null ? child.find(segments, offset + 1) : null;
    }

    public Path getPath() {
        return path;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public PhysicalSnapshot add(String[] segments, int offset, PhysicalSnapshot snapshot) {
        if (segments.length == offset) {
            return this;
        }
        String currentSegment = segments[offset];
        PhysicalSnapshot child = children.get(currentSegment);
        if (child == null) {
            PhysicalSnapshot newChild;
            if (segments.length == offset + 1) {
                newChild = snapshot;
            } else {
                newChild = new PhysicalDirectorySnapshot(path.resolve(currentSegment), currentSegment);
            }
            child = add(currentSegment, newChild);
        }
        return child.add(segments, offset + 1, snapshot);
    }

    @Override
    public void visitTree(PhysicalFileVisitor visitor, Deque<String> relativePath) {
        for (Map.Entry<String, PhysicalSnapshot> entry : children.entrySet()) {
            relativePath.addLast(entry.getKey());
            entry.getValue().visitSelf(visitor, relativePath);
            entry.getValue().visitTree(visitor, relativePath);
            relativePath.removeLast();
        }
    }

    @Override
    public void visitSelf(PhysicalFileVisitor visitor, Deque<String> relativePath) {
        visitor.visit(path, name, relativePath, DirContentSnapshot.INSTANCE);
    }

    public <T extends PhysicalSnapshot> T add(String relativePath, T snapshot) {
        PhysicalSnapshot child = children.putIfAbsent(relativePath, snapshot);
        if (child == null) {
                child = snapshot;
            } else {
                Preconditions.checkState(snapshot.getClass().equals(child.getClass()), "Expected different snapshot type: requested %s, but was: %s", snapshot.getClass().getSimpleName(), child.getClass().getSimpleName());
        }
        return Cast.uncheckedCast(child);
    }
}
