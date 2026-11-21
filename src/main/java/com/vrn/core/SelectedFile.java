package com.vrn.core;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Actual source spelling, recorded PUT spelling, and selection-time stability indicators. */
record SelectedFile(Path source, Path path, BasicFileAttributes observed) {}
