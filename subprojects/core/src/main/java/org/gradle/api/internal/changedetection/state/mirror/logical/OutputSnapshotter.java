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

import org.gradle.api.internal.changedetection.state.FileContentSnapshot;
import org.gradle.api.internal.changedetection.state.FileSnapshot;
import org.gradle.api.internal.changedetection.state.FileSystemSnapshotter;
import org.gradle.api.internal.changedetection.state.mirror.HierarchicalFileTreeVisitor;
import org.gradle.api.internal.changedetection.state.mirror.PhysicalSnapshotBackedVisitableTree;
import org.gradle.api.internal.changedetection.state.mirror.VisitableDirectoryTree;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.internal.file.FileCollectionVisitor;
import org.gradle.api.internal.file.FileTreeInternal;
import org.gradle.api.internal.file.collections.DirectoryFileTree;
import org.gradle.api.internal.file.collections.DirectoryFileTreeFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

public class OutputSnapshotter {

    private final FileSystemSnapshotter snapshotter;
    private final DirectoryFileTreeFactory directoryFileTreeFactory;

    public OutputSnapshotter(FileSystemSnapshotter snapshotter, DirectoryFileTreeFactory directoryFileTreeFactory) {
        this.snapshotter = snapshotter;
        this.directoryFileTreeFactory = directoryFileTreeFactory;
    }

    public LogicalFileCollectionSnapshot snapshot(FileCollectionInternal output) {
        final LogicalOutputSnapshotBuilder builder = new LogicalOutputSnapshotBuilder();
        output.visitRootElements(new FileCollectionVisitor() {
            @Override
            public void visitCollection(FileCollectionInternal fileCollection) {
                for (File file : fileCollection) {
                    FileSnapshot fileSnapshot = snapshotter.snapshotSelf(file);
                    switch (fileSnapshot.getType()) {
                        case Missing:
                            break; // TODO: Ignored for now
                        case RegularFile:
                            builder.addRoot(fileSnapshot.getPath(), fileSnapshot.getName(), fileSnapshot.getContent());
                            break;
                        case Directory:
                            builder.addRoot(fileSnapshot.getPath(), fileSnapshot.getName(), fileSnapshot.getContent());
                            visitDirectoryTree(directoryFileTreeFactory.create(file));
                    }
                }
            }

            @Override
            public void visitTree(FileTreeInternal fileTree) {
                throw new UnsupportedOperationException("Snapshotting FileTreeInternal is not yet supported");
            }

            @Override
            public void visitDirectoryTree(DirectoryFileTree directoryTree) {
                VisitableDirectoryTree visitableDirectoryTree = snapshotter.snapshotDirectoryTree(directoryTree);
                if (!(visitableDirectoryTree instanceof PhysicalSnapshotBackedVisitableTree)) {
                    throw new UnsupportedOperationException("Only PhysicalSnapshotBacked trees allowed! Was: " + visitableDirectoryTree.getClass().getName());
                }

                PhysicalSnapshotBackedVisitableTree tree = (PhysicalSnapshotBackedVisitableTree) visitableDirectoryTree;
                final LogicalDirectorySnapshot treeSnapshot = builder.snapshot.findOrCreateTree(tree.getBasePath());

                tree.getRootDirectory().visit(new HierarchicalFileTreeVisitor() {
                    private final Deque<LogicalDirectorySnapshot> trees = new ArrayDeque<LogicalDirectorySnapshot>();

                    @Override
                    public void preVisitDirectory(Path path, String name) {
                        if (trees.isEmpty()) {
                            trees.addLast(treeSnapshot);
                        } else {
                            LogicalDirectorySnapshot snapshot = new LogicalDirectorySnapshot(name);
                            trees.peekLast().getChildren().put(name, snapshot);
                            trees.addLast(snapshot);
                        }
                    }

                    @Override
                    public void visit(Path path, String name, FileContentSnapshot content) {
                        trees.peekLast().getChildren().put(name, new LogicalFileSnapshot(name, content));
                    }

                    @Override
                    public void postVisitDirectory() {
                        trees.removeLast();
                    }
                });
            }
        });
        return builder.build();
    }

    private static class LogicalOutputSnapshotBuilder {
        private final LogicalFileCollectionSnapshot snapshot;

        private LogicalOutputSnapshotBuilder() {
            this.snapshot = new LogicalFileCollectionSnapshot();
        }

        public void addRoot(String path, String name, FileContentSnapshot content) {
            LogicalSnapshot root = snapshot.findPossibleRoot(path);
            if (root == null) {
                snapshot.addRoot(path, name, content);
            }
        }

        public LogicalFileCollectionSnapshot build() {
            return snapshot;
        }
    }
}
