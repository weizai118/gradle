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

package org.gradle.api.internal.changedetection.state.mirror

import groovy.io.FileType
import org.gradle.api.Action
import org.gradle.api.internal.cache.StringInterner
import org.gradle.api.internal.changedetection.rules.ChangeType
import org.gradle.api.internal.changedetection.rules.FileChange
import org.gradle.api.internal.changedetection.state.DefaultFileSystemMirror
import org.gradle.api.internal.changedetection.state.DefaultFileSystemSnapshotter
import org.gradle.api.internal.changedetection.state.DefaultWellKnownFileLocations
import org.gradle.api.internal.changedetection.state.mirror.logical.LogicalFileCollectionSnapshot
import org.gradle.api.internal.changedetection.state.mirror.logical.OutputSnapshotter
import org.gradle.api.internal.file.IdentityFileResolver
import org.gradle.api.internal.file.TestFiles
import org.gradle.api.internal.file.collections.DefaultConfigurableFileCollection
import org.gradle.api.internal.file.collections.ImmutableFileCollection
import org.gradle.api.internal.tasks.TaskResolver
import org.gradle.test.fixtures.file.CleanupTestDirectory
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.gradle.util.UsesNativeServices
import org.junit.Rule
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicBoolean

@UsesNativeServices
@CleanupTestDirectory
class OutputSnapshotterTest extends Specification {
    @Rule
    TestNameTestDirectoryProvider temporaryFolder = new TestNameTestDirectoryProvider()

    def fileSystemMirror = new DefaultFileSystemMirror(new DefaultWellKnownFileLocations([]))
    def fileSystemSnapshotter = new DefaultFileSystemSnapshotter(TestFiles.fileHasher(), new StringInterner(), TestFiles.fileSystem(), TestFiles.directoryFileTreeFactory(), fileSystemMirror)
    def outputSnapshotter = new OutputSnapshotter(fileSystemSnapshotter, TestFiles.directoryFileTreeFactory())

    def "snapshots outputs"() {
        def dir1 = temporaryFolder.createDir("build/classes/java/main")
        def dir2 = temporaryFolder.createDir("build/classes/java/some")
        createClassesDir(dir1)
        createClassesDir(dir2)

        def dir3 = temporaryFolder.createDir("build/tmp/someTask")
        dir3.file("output.txt")  << "Output"
        def file1 = temporaryFolder.createFile("build/output.txt")
        file1  << "Output file"

        when:
        def snapshot = outputSnapshotter.snapshot(files(dir1, dir2, ImmutableFileCollection.of(dir3).asFileTree, file1))

        then:
        snapshot.roots.keySet() == [dir1.absolutePath, dir2.absolutePath, dir3.absolutePath, file1.absolutePath] as Set
    }

    def "detects changes"() {
        def dir1 = temporaryFolder.createDir("build/classes/java/main")
        createClassesDir(dir1)
        def dir2 = temporaryFolder.createDir("build/classes/java/some")
        createClassesDir(dir2)
        def dir3 = temporaryFolder.createDir('build/tmp')
        createClassesDir(dir3)
        def rootFile1 = temporaryFolder.createFile('rootFile1.txt')
        def rootFile2 = temporaryFolder.createFile('rootFile2.txt')
        def modifiedFile = dir3.file('org/gradle/package/File3.class')
        modifiedFile.text = "original"

        when:
        def snapshotBefore = outputSnapshotter.snapshot(files(dir1, dir3, rootFile1))
        fileSystemMirror.beforeTaskOutputChanged()
        modifiedFile.text = "modified"
        def snapshotAfter = outputSnapshotter.snapshot(files(dir2, dir3, rootFile2))
        List changes = getChanges(snapshotBefore, snapshotAfter)

        then:
        changes.findAll { it.change == ChangeType.REMOVED }*.path as Set == allFilesInDir(dir1) + [rootFile1.absolutePath]
        changes.findAll { it.change == ChangeType.ADDED }*.path as Set == allFilesInDir(dir2) + [rootFile2.absolutePath]
        changes.findAll { it.change == ChangeType.MODIFIED }*.path == [modifiedFile.absolutePath]
    }

    def "detects root file replaced by root directory"() {
        def root = temporaryFolder.file("build/output")

        when:
        root.text = "is a File"
        def snapshotBefore = outputSnapshotter.snapshot(files(root))
        fileSystemMirror.beforeTaskOutputChanged()
        root.delete()
        root.createDir()
        def snapshotAfter = outputSnapshotter.snapshot(files(root))
        def changes = getChanges(snapshotBefore, snapshotAfter)

        then:
        changes.findAll { it.change == ChangeType.MODIFIED }*.path == [root.absolutePath]
        changes.findAll { it. change != ChangeType.MODIFIED }.empty
    }

    def "detects root directory replaced by root file"() {
        def root = temporaryFolder.file("build/output")

        when:
        root.createDir()
        def snapshotBefore = outputSnapshotter.snapshot(files(root))
        fileSystemMirror.beforeTaskOutputChanged()
        root.deleteDir()
        root.text = "is a File"
        def snapshotAfter = outputSnapshotter.snapshot(files(root))
        def changes = getChanges(snapshotBefore, snapshotAfter)

        then:
        changes.findAll { it.change == ChangeType.MODIFIED }*.path == [root.absolutePath]
        changes.findAll { it. change != ChangeType.MODIFIED }.empty
    }

    private static List<FileChange> getChanges(LogicalFileCollectionSnapshot snapshotBefore, LogicalFileCollectionSnapshot snapshotAfter) {
        def stopFlag = new AtomicBoolean()
        def changes = []
        snapshotAfter.visitDifferences(new Action<FileChange>() {
            @Override
            void execute(FileChange fileChange) {
                changes << fileChange
            }
        }, stopFlag, snapshotBefore)
        changes
    }

    private static Set<String> allFilesInDir(TestFile dir) {
        def allDir2Files = [dir.absolutePath] as Set
        dir.eachFileRecurse(FileType.ANY) { file ->
            allDir2Files << file.absolutePath
        }
        allDir2Files
    }

    private static void createClassesDir(TestFile dir) {
        def class1 = dir.file("org/gradle/package/File1.class")
        def class2 = dir.file("org/gradle/package/File2.class")
        def class3 = dir.file("org/gradle/util/Util.class")
        def class4 = dir.file("org/some/different/Different.class")
        [class1, class2, class3, class4].each { it.text = it.name }
    }

    def files(Object... paths) {
        new DefaultConfigurableFileCollection(new IdentityFileResolver(TestFiles.fileSystem(), TestFiles.patternSetFactory), Stub(TaskResolver), paths)
    }

}
