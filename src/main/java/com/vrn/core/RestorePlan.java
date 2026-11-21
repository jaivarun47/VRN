package com.vrn.core;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

/** Immutable observation of a whole-snapshot working-tree restore. Never an execution permit. */
public record RestorePlan(Path projectRoot, SnapshotResolver.Selection selection, List<Action> actions,
                          List<ExpectedPath> expectedPaths, List<DirectoryChange> directoryChanges) {
    public RestorePlan {
        actions = List.copyOf(actions);
        expectedPaths = List.copyOf(expectedPaths);
        directoryChanges = List.copyOf(directoryChanges);
    }

    public boolean isSafe() { return actions.stream().allMatch(action -> action.safety() == Safety.SAFE); }

    public enum Operation { CREATE, REPLACE, REMOVE, KEEP }
    public enum Safety { SAFE, CONFLICT, UNSUPPORTED }
    public enum Type { MISSING, FILE, DIRECTORY, UNSUPPORTED }

    /** Prerequisites name other file actions; ordering/directory execution is deferred to 9C. */
    public record Action(Operation operation, String path, String currentPath, String headPath,
                         String headHash, String targetHash, boolean requiresChange, Safety safety,
                         List<String> reasons, List<String> prerequisites) {
        public Action { reasons = List.copyOf(reasons); prerequisites = List.copyOf(prerequisites); }
    }

    /** Null children means unlisted, distinct from an observed empty directory. */
    public record ExpectedPath(String requestedPath, String actualPath, Type type, String contentHash,
                               String fileKey, long size, FileTime modified, FileTime created,
                               List<String> children, String blockingAncestor, String problem) {
        public ExpectedPath { if (children != null) children = List.copyOf(children); }
    }

    /** Whole-directory spelling transition; all affected observations must be revalidated together. */
    public record DirectoryChange(String currentPath, String targetPath) {}
}
