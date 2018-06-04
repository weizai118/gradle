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
import com.google.common.collect.ImmutableMap;
import org.gradle.api.GradleException;
import org.gradle.api.file.RelativePath;
import org.gradle.internal.file.FileType;
import org.gradle.internal.hash.FileHasher;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.nativeintegration.filesystem.DefaultFileMetadata;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("Since15")
public class MirrorUpdatingDirectoryWalker {
    private final FileHasher hasher;


    public MirrorUpdatingDirectoryWalker(FileHasher hasher) {
        this.hasher = hasher;
    }

    public ImmutablePhysicalDirectorySnapshot walkDir(final Path rootPath) {
        final Deque<String> relativePathHolder = new ArrayDeque<String>();
        final Deque<ImmutableMap.Builder<String, PhysicalSnapshot>> levelHolder = new ArrayDeque<ImmutableMap.Builder<String, PhysicalSnapshot>>();
        final AtomicReference<ImmutablePhysicalDirectorySnapshot> result = new AtomicReference<ImmutablePhysicalDirectorySnapshot>();

        try {
            Files.walkFileTree(rootPath, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new java.nio.file.FileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    levelHolder.addLast(ImmutableMap.<String, PhysicalSnapshot>builder());
                    String name = internedName(dir);
                    relativePathHolder.addLast(name);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, @Nullable BasicFileAttributes attrs) {
                    if (attrs != null && attrs.isSymbolicLink()) {
                        // when FileVisitOption.FOLLOW_LINKS, we only get here when link couldn't be followed
                        throw new GradleException(String.format("Could not list contents of '%s'. Couldn't follow symbolic link.", file));
                    }
                    addFileSnapshot(file, attrs);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, @Nullable IOException exc) {
                    if (isNotFileSystemLoopException(exc)) {
                        throw new GradleException(String.format("Could not read path '%s'.", file), exc);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException exc) {
                    if (isNotFileSystemLoopException(exc)) {
                        throw new GradleException(String.format("Could not read directory path '%s'.", dir), exc);
                    }
                    String directoryPath = relativePathHolder.removeLast();
                    ImmutableMap.Builder<String, PhysicalSnapshot> builder = levelHolder.removeLast();
                    ImmutablePhysicalDirectorySnapshot directorySnapshot = new ImmutablePhysicalDirectorySnapshot(dir, directoryPath, builder.build());
                    ImmutableMap.Builder<String, PhysicalSnapshot> parentBuilder = levelHolder.peekLast();
                    if (parentBuilder != null) {
                        parentBuilder.put(directoryPath, directorySnapshot);
                    } else {
                        result.set(directorySnapshot);
                    }
                    return FileVisitResult.CONTINUE;
                }

                private boolean isNotFileSystemLoopException(@Nullable IOException e) {
                    return e != null && !(e instanceof FileSystemLoopException);
                }

                private void addFileSnapshot(Path file, @Nullable BasicFileAttributes attrs) {
                    Preconditions.checkNotNull(attrs, "Unauthorized access to %", file);
                    String name = internedName(file);
                    DefaultFileMetadata metadata = new DefaultFileMetadata(FileType.RegularFile, attrs.lastModifiedTime().toMillis(), attrs.size());
                    HashCode hash = hasher.hash(file.toFile(), metadata);
                    PhysicalFileSnapshot fileSnapshot = new PhysicalFileSnapshot(file, name, metadata.getLastModified(), hash);
                    levelHolder.peekLast().put(name, fileSnapshot);
                }

                private String internedName(Path dir) {
                    return RelativePath.PATH_SEGMENT_STRING_INTERNER.intern(dir.getFileName().toString());
                }
            });
        } catch (IOException e) {
            throw new GradleException(String.format("Could not list contents of directory '%s'.", rootPath), e);
        }
        return result.get();
    }

}
