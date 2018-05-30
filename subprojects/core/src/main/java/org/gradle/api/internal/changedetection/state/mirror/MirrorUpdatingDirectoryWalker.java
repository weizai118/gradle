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
import org.gradle.api.GradleException;
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

@SuppressWarnings("Since15")
public class MirrorUpdatingDirectoryWalker {
    private final FileHasher hasher;


    public MirrorUpdatingDirectoryWalker(FileHasher hasher) {
        this.hasher = hasher;
    }

    public void walkDir(final Path rootPath, final PhysicalDirectorySnapshot rootDirectory) {
        final Deque<PhysicalDirectorySnapshot> relativePathHolder = new ArrayDeque<PhysicalDirectorySnapshot>();

        try {
            Files.walkFileTree(rootPath, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new java.nio.file.FileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(rootPath)) {
                        return FileVisitResult.CONTINUE;
                    }
                    PhysicalDirectorySnapshot snapshot = getDirectorySnapshot(dir);
                    relativePathHolder.addLast(snapshot);
                    return FileVisitResult.CONTINUE;
                }

                private PhysicalDirectorySnapshot getParentSnapshot() {
                    return relativePathHolder.isEmpty() ? rootDirectory : relativePathHolder.peekLast();
                }

                @Override
                public FileVisitResult visitFile(Path file, @Nullable BasicFileAttributes attrs) {
                    if (attrs != null && attrs.isSymbolicLink()) {
                        // when FileVisitOption.FOLLOW_LINKS, we only get here when link couldn't be followed
                        throw new GradleException(String.format("Could not list contents of '%s'. Couldn't follow symbolic link.", file));
                    }
                    getFileSnapshot(file, attrs);
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
                    if (!dir.equals(rootPath)) {
                        relativePathHolder.pop();
                    }
                    return FileVisitResult.CONTINUE;
                }

                private boolean isNotFileSystemLoopException(@Nullable IOException e) {
                    return e != null && !(e instanceof FileSystemLoopException);
                }

                private PhysicalDirectorySnapshot getDirectorySnapshot(Path dir) {
                    String name = dir.getFileName().toString();
                    return getParentSnapshot().add(name, new PhysicalDirectorySnapshot(name));
                }

                private PhysicalFileSnapshot getFileSnapshot(Path file, @Nullable BasicFileAttributes attrs) {
                    Preconditions.checkNotNull(attrs, "Unauthorized access to %", file);
                    String name = file.getFileName().toString();
                    DefaultFileMetadata metadata = new DefaultFileMetadata(FileType.RegularFile, attrs.lastModifiedTime().toMillis(), attrs.size());
                    HashCode hash = hasher.hash(file.toFile(), metadata);
                    PhysicalFileSnapshot fileSnapshot = new PhysicalFileSnapshot(name, metadata.getLastModified(), hash);
                    return getParentSnapshot().add(name, fileSnapshot);
                }
            });
        } catch (IOException e) {
            throw new GradleException(String.format("Could not list contents of directory '%s'.", rootPath), e);
        }
    }

}
