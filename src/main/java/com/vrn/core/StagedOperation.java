package com.vrn.core;

/** One current selection for a path. Removing a selection means inheritance from HEAD. */
public sealed interface StagedOperation permits StagedOperation.Put, StagedOperation.Delete {
    record Put(String contentHash) implements StagedOperation {
        public Put { MetadataCodec.validateObjectReference(contentHash); }
    }

    record Delete() implements StagedOperation {}
}
