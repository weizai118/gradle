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

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import org.gradle.api.Action;
import org.gradle.api.internal.changedetection.rules.FileChange;
import org.gradle.api.internal.changedetection.state.FileContentSnapshot;
import org.gradle.internal.FileUtils;
import org.gradle.internal.file.FileType;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class LogicalFileCollectionSnapshot {
    private static final String TITLE = "Output";
    private final Map<String, LogicalSnapshot> roots = new HashMap<String, LogicalSnapshot>();

    public LogicalDirectorySnapshot findOrCreateTree(String basePath) {
        LogicalSnapshot root = findPossibleRoot(basePath);
        if (root == null) {
            root = new LogicalDirectorySnapshot("no-name");
            roots.put(basePath, root);
        }
        if (!(root instanceof LogicalDirectorySnapshot)) {
            throw new UnsupportedOperationException("File cannot be the root of a tree");
        }
        return (LogicalDirectorySnapshot) root;
    }

    public void addRoot(String path, String name, FileContentSnapshot contentSnapshot) {
        LogicalSnapshot root;
        switch (contentSnapshot.getType()) {
            case RegularFile:
                root = new LogicalFileSnapshot(name, contentSnapshot);
                break;
            case Directory:
                root = new LogicalDirectorySnapshot(name);
                break;
            default:
                throw new IllegalArgumentException("Unsupported type: " + contentSnapshot.getType());
        }
        roots.put(path, root);
    }

    @Nullable
    public LogicalSnapshot findPossibleRoot(String basePath) {
        for (Map.Entry<String, LogicalSnapshot> entry : roots.entrySet()) {
            String candidatePath = entry.getKey();
            if (candidatePath.equals(basePath)) {
                return entry.getValue();
            }
            if (FileUtils.doesPathStartWith(basePath, candidatePath)) {
                throw new UnsupportedOperationException("Cannot have nodes nested in one another");
            }
            if (FileUtils.doesPathStartWith(candidatePath, basePath)) {
                throw new UnsupportedOperationException("Split root paths - Not yet implemented.");
            }
        }
        return null;
    }

    public Map<String, LogicalSnapshot> getRoots() {
        return roots;
    }

    public void visitDifferences(final Action<FileChange> changeVisitor, AtomicBoolean stopFlag, LogicalFileCollectionSnapshot old) {
        if (!roots.keySet().equals(old.roots.keySet())) {
            Set<String> added = new HashSet<String>(roots.keySet());
            added.removeAll(old.roots.keySet());
            visitChanges(added, this, new Added(changeVisitor), stopFlag);

            if (stopFlag.get()) {
                return;
            }
            Set<String> removed = new HashSet<String>(old.getRoots().keySet());
            removed.removeAll(roots.keySet());
            visitChanges(removed, old, new Removed(changeVisitor), stopFlag);
        }
        for (Map.Entry<String, LogicalSnapshot> entry : roots.entrySet()) {
            if (stopFlag.get()) {
                return;
            }
            LogicalSnapshot newValue = entry.getValue();
            String basePath = entry.getKey();
            LogicalSnapshot oldValue = old.getRoots().get(basePath);
            if (oldValue != null) {
                compare(basePath, newValue, oldValue, changeVisitor, stopFlag);
            }
        }
    }

    private void compare(final String basePath, final LogicalSnapshot newRoot, final LogicalSnapshot oldRoot, final Action<FileChange> changeVisitor, final AtomicBoolean stopFlag) {
        System.out.println("Comparing " + basePath);
        newRoot.accept(new PathTrackingLogicalSnapshotVisitor(basePath, newRoot) {
            private Deque<LogicalDirectorySnapshot> currentOldValue = Lists.newLinkedList();
            private Deque<List<String>> visitedChilds = Lists.newLinkedList();

            @Override
            public boolean visitFile(final LogicalFileSnapshot file) {
                if (!isRoot(file)) {
                    visitedChilds.peekLast().add(file.getName());
                }
                LogicalSnapshot oldSnapshot = getOldValue(file, file.getName());
                System.out.println("Check if same: " + getAbsolutePath(file.getName()));
                if (oldSnapshot == null) {
                    changeVisitor.execute(FileChange.added(getAbsolutePath(file.getName()), TITLE, FileType.RegularFile));
                    return !stopFlag.get();
                }
                oldSnapshot.accept(new LogicalSnapshotVisitor() {
                    @Override
                    public boolean visitFile(LogicalFileSnapshot oldFile) {
                        System.out.println("Comparing file with file at: " + getAbsolutePath(file.getName()));
                        if (!file.getContent().isContentUpToDate(oldFile.getContent())) {
                            System.out.println("File different at: " + getAbsolutePath(file.getName()));
                            changeVisitor.execute(FileChange.modified(getAbsolutePath(file.getName()), TITLE, FileType.RegularFile, FileType.RegularFile));
                        }
                        return false;
                    }

                    @Override
                    public boolean visitEnter(LogicalDirectorySnapshot oldDir) {
                        System.out.println("File replaced by directory:" + getAbsolutePath(file.getName()));
                        final String absolutePath = getAbsolutePath(file.getName());
                        changeVisitor.execute(FileChange.modified(absolutePath, TITLE, FileType.Directory, FileType.RegularFile));
                        oldDir.accept(new AddSubtreeChanges(absolutePath, oldDir, new Removed(changeVisitor), stopFlag, false));
                        return true;
                    }

                    @Override
                    public boolean visitLeave(LogicalDirectorySnapshot tree) {
                        return false;
                    }
                });
                return !stopFlag.get();
            }

            @Override
            public boolean visitEnter(final LogicalDirectorySnapshot dir) {
                super.visitEnter(dir);
                if (!isRoot(dir)) {
                    visitedChilds.peekLast().add(dir.getName());
                }
                visitedChilds.addLast(new ArrayList<String>());

                LogicalSnapshot oldSnapshot = getOldValue(dir, dir.getName());
                if (oldSnapshot == null) {
                    dir.accept(new AddSubtreeChanges(getAbsolutePath(), dir, new Added(changeVisitor), stopFlag, true));
                    return false;
                }
                boolean isFile = oldSnapshot.accept(new LogicalSnapshotVisitor() {
                    @Override
                    public boolean visitFile(LogicalFileSnapshot file) {
                        final String absolutePath = getAbsolutePath();
                        changeVisitor.execute(FileChange.modified(absolutePath, TITLE, FileType.RegularFile, FileType.Directory));
                        dir.accept(new AddSubtreeChanges(absolutePath, dir, new Added(changeVisitor), stopFlag, false));
                        return true;
                    }

                    @Override
                    public boolean visitEnter(LogicalDirectorySnapshot tree) {
                        currentOldValue.addLast(tree);
                        return false;
                    }

                    @Override
                    public boolean visitLeave(LogicalDirectorySnapshot tree) {
                        return false;
                    }
                });
                return !isFile && !stopFlag.get();
            }

            @Override
            public boolean visitLeave(LogicalDirectorySnapshot dir) {
                LogicalDirectorySnapshot oldValue = currentOldValue.removeLast();
                List<String> visitedNewChilds = visitedChilds.removeLast();
                Set<String> removed = new HashSet<String>(oldValue.getChildren().keySet());
                removed.removeAll(visitedNewChilds);
                for (String name : removed) {
                    LogicalSnapshot logicalSnapshot = oldValue.getChildren().get(name);
                    logicalSnapshot.accept(new AddSubtreeChanges(getAbsolutePath(name), logicalSnapshot, new Removed(changeVisitor), stopFlag, true));
                }

                super.visitLeave(dir);
                return !stopFlag.get();
            }

            private LogicalSnapshot getOldValue(LogicalSnapshot newSnapshot, String name) {
                if (newSnapshot == newRoot) {
                    return oldRoot;
                }
                return currentOldValue.peekLast().getChildren().get(name);
            }
        });
    }

    private void visitChanges(Set<String> rootPaths, LogicalFileCollectionSnapshot logicalSnapshot, final FileChangeVisitor visitor, final AtomicBoolean stopFlag) {
        for (final String rootPath : rootPaths) {
            if (stopFlag.get()) {
                return;
            }
            LogicalSnapshot snapshot = logicalSnapshot.getRoots().get(rootPath);
            snapshot.accept(new AddSubtreeChanges(rootPath, snapshot, visitor, stopFlag, true));
        }
    }

    private static abstract class PathTrackingLogicalSnapshotVisitor implements LogicalSnapshotVisitor {
        private final String rootPath;
        private final LogicalSnapshot root;
        private Deque<String> relativePath = new LinkedList<String>();

        public PathTrackingLogicalSnapshotVisitor(String rootPath, LogicalSnapshot root) {
            this.rootPath = rootPath;
            this.root = root;
        }

        @Override
        public boolean visitEnter(LogicalDirectorySnapshot dir) {
            if (!isRoot(dir)) {
                relativePath.addLast(dir.getName());
            }
            return true;
        }

        @Override
        public boolean visitLeave(LogicalDirectorySnapshot dir) {
            if (!isRoot(dir)) {
                relativePath.removeLast();
            }
            return true;
        }

        protected Deque<String> getRelativePath() {
            return relativePath;
        }

        protected String getAbsolutePath(String name) {
            if (relativePath.isEmpty()) {
                return rootPath;
            }
            Joiner joiner = Joiner.on(File.separatorChar).skipNulls();
            return joiner.join(rootPath, Strings.emptyToNull(joiner.join(relativePath)), name);
        }

        protected String getAbsolutePath() {
            if (relativePath.isEmpty()) {
                return rootPath;
            }
            Joiner joiner = Joiner.on(File.separatorChar).skipNulls();
            return joiner.join(rootPath, Strings.emptyToNull(joiner.join(relativePath)));
        }

        protected boolean isRoot(LogicalSnapshot root) {
            return root == this.root;
        }
    }

    private interface FileChangeVisitor {
        void change(String path, FileType currentFileType);
    }

    private static class AddSubtreeChanges extends PathTrackingLogicalSnapshotVisitor {
        private final FileChangeVisitor visitor;
        private final AtomicBoolean stopFlag;
        private final boolean reportRoot;

        public AddSubtreeChanges(String rootPath, LogicalSnapshot snapshot, FileChangeVisitor visitor, AtomicBoolean stopFlag, boolean reportRoot) {
            super(rootPath, snapshot);
            this.visitor = visitor;
            this.stopFlag = stopFlag;
            this.reportRoot = reportRoot;
        }

        @Override
        public boolean visitFile(LogicalFileSnapshot file) {
            if (!isRoot(file) || reportRoot) {
                visitor.change(getAbsolutePath(file.getName()), FileType.RegularFile);
            }
            return !stopFlag.get();
        }

        @Override
        public boolean visitEnter(LogicalDirectorySnapshot dir) {
            super.visitEnter(dir);
            if (!isRoot(dir) || reportRoot) {
                visitor.change(getAbsolutePath(), FileType.Directory);
            }
            return !stopFlag.get();
        }
    }

    private static class Removed implements FileChangeVisitor {
        private final Action<FileChange> changeVisitor;

        public Removed(Action<FileChange> changeVisitor) {
            this.changeVisitor = changeVisitor;
        }

        @Override
        public void change(String path, FileType currentFileType) {
            changeVisitor.execute(FileChange.removed(path, TITLE, currentFileType));
        }
    }

    private static class Added implements FileChangeVisitor {
        private final Action<FileChange> changeVisitor;

        public Added(Action<FileChange> changeVisitor) {
            this.changeVisitor = changeVisitor;
        }

        @Override
        public void change(String path, FileType currentFileType) {
            changeVisitor.execute(FileChange.added(path, TITLE, currentFileType));
        }
    }
}
