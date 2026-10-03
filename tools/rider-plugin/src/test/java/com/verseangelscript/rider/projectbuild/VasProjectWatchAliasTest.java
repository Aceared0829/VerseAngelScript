package com.verseangelscript.rider.projectbuild;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public final class VasProjectWatchAliasTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void existingInputsKeepTheirSnapshotIdentity() throws Exception {
        Path original = temporary.newFile("source.vas").toPath();
        Files.writeString(original, "void main() {}\n");
        var snapshot = VasProjectInputs.capture(original);
        assertEquals(original.toRealPath(), VasProjectInputs.watchAlias(original));
        assertEquals(original, snapshot.path());
        assertTrue(snapshot.unchanged(true));
    }

    @Test public void nestedMissingSuffixWatchesOnlyCandidateAndItsAncestors() throws Exception {
        Path parent = temporary.newFolder("external").toPath();
        Path original = parent.resolve("uncreated/includes/missing.vas");
        var snapshot = VasProjectInputs.capture(original);
        Path alias = VasProjectInputs.watchAlias(original);
        Path realParent = parent.toRealPath();
        assertEquals(realParent.resolve("uncreated/includes/missing.vas"), alias);
        assertTrue(alias.startsWith(realParent));
        assertFalse(alias.startsWith(realParent.resolve("unrelated")));
        assertNotEquals(realParent.resolve("unrelated.txt"), alias);
        Files.writeString(realParent.resolve("unrelated.txt"), "unrelated");
        assertTrue(snapshot.unchanged(true));
        Files.createDirectories(alias.getParent());
        Files.writeString(alias, "void nowExists() {}\n");
        assertFalse(snapshot.unchanged(true));
        assertEquals(original, snapshot.path());
    }

    @Test public void aliasedMissingParentRetainsEveryMissingComponent() throws Exception {
        Path root = temporary.getRoot().toPath().toAbsolutePath();
        Path spelledParent = root.resolve("external-alias");
        Path realParent = root.resolve("physical-external");
        Path original = spelledParent.resolve("absent/nested/文😀.vas");
        List<Path> attempts = new ArrayList<>();
        Path alias = VasProjectInputs.watchAlias(original, path -> {
            attempts.add(path);
            if (path.equals(spelledParent)) return realParent;
            throw new NoSuchFileException(path.toString());
        });
        assertEquals(realParent.resolve("absent/nested/文😀.vas"), alias);
        assertEquals(List.of(original, original.getParent(), spelledParent.resolve("absent"), spelledParent), attempts);
        assertNotEquals(original, alias);
        assertFalse(alias.startsWith(realParent.resolve("other")));
    }

    @Test public void nonAbsenceFailuresDoNotBroadenTheWatchToAnAncestor() {
        Path original = temporary.getRoot().toPath().resolve("missing.vas");
        for (IOException failure : List.of(new AccessDeniedException("denied"),
            new FileSystemLoopException("loop"), new NotDirectoryException("file-parent"),
            new FileSystemException("unavailable"))) {
            List<Path> attempts = new ArrayList<>();
            assertSame(failure, assertThrows(IOException.class, () -> VasProjectInputs.watchAlias(original, path -> {
                attempts.add(path);
                throw failure;
            })));
            assertEquals(List.of(original.toAbsolutePath()), attempts);
        }
    }

    @Test public void deniedNearestAncestorDoesNotInventAnAliasFromHigherParents() {
        Path original = temporary.getRoot().toPath().resolve("missing.vas").toAbsolutePath();
        var denied = new AccessDeniedException(original.getParent().toString());
        List<Path> attempts = new ArrayList<>();
        assertSame(denied, assertThrows(IOException.class, () -> VasProjectInputs.watchAlias(original, path -> {
            attempts.add(path);
            if (path.equals(original)) throw new NoSuchFileException(path.toString());
            throw denied;
        })));
        assertEquals(List.of(original, original.getParent()), attempts);
    }

    @Test public void unavailableRootTerminatesWithinThePathDepth() {
        Path original = temporary.getRoot().toPath().resolve("absent/missing.vas").toAbsolutePath();
        List<Path> attempts = new ArrayList<>();
        assertThrows(NoSuchFileException.class, () -> VasProjectInputs.watchAlias(original, path -> {
            attempts.add(path);
            throw new NoSuchFileException(path.toString());
        }));
        assertEquals(original.getNameCount() + 1, attempts.size());
        assertEquals(original.getRoot(), attempts.getLast());
    }
}
