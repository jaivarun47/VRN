package com.vrn.core;

import java.nio.file.Path;

public record ScannedFile(Path path, String hash) {
}