package com.verseangelscript.rider.index;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

/** Read-only editor search hints. Does not claim compiler dependency/rename coverage. */
public final class VasDependencyPaths {
    private VasDependencyPaths() { }

    public static VirtualFile resolve(PsiFile source, VirtualFile entryDirectory,
                                      String path, VasIncludeScanner.Kind kind) {
        VirtualFile file = source.getVirtualFile();
        if (file == null || file.getParent() == null || path.startsWith("/") || path.indexOf(':') >= 0) return null;
        String normalized = path.replace('\\', '/');
        if (kind != VasIncludeScanner.Kind.SYSTEM) {
            VirtualFile local = file.getParent().findFileByRelativePath(normalized);
            if (isFile(local)) return local;
        }
        Set<VirtualFile> roots = new LinkedHashSet<>();
        if (entryDirectory != null) roots.add(entryDirectory);
        // A library opened independently has no selected compilation unit. Retain
        // possible entry roots up to the project boundary instead of searching the disk.
        for (VirtualFile parent = file.getParent(); parent != null; parent = parent.getParent()) {
            roots.add(parent);
            if (parent.findChild("vas-project.json") != null || parent.findChild(".git") != null
                || parent.getPath().equals(source.getProject().getBasePath())) break;
        }
        String environment = System.getenv("VAS_INCLUDE_PATH");
        if (environment != null) {
            for (String value : environment.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (value.isBlank()) continue;
                File directory = new File(value);
                if (!directory.isAbsolute() && entryDirectory != null) directory = new File(entryDirectory.getPath(), value);
                VirtualFile root = LocalFileSystem.getInstance().findFileByPath(directory.getPath().replace('\\', '/'));
                if (root != null) roots.add(root);
            }
        }
        Set<VirtualFile> matches = new LinkedHashSet<>();
        for (VirtualFile root : roots) {
            ProgressManager.checkCanceled();
            VirtualFile target = root.findFileByRelativePath(normalized);
            if (isFile(target)) matches.add(target);
        }
        return matches.size() == 1 ? matches.iterator().next() : null;
    }

    private static boolean isFile(VirtualFile file) {
        return file != null && file.isValid() && !file.isDirectory();
    }
}
