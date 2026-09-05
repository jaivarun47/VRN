package com.vrn.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class StagingArea {

    private final Path stagingFile;

    public StagingArea(Path vrnDirectory) {
        this.stagingFile = vrnDirectory
                .resolve("staging")
                .resolve("index");
    }

    public void stage(Path path, String hash) throws Exception {

        Files.createDirectories(stagingFile.getParent());

        String entry = path.toString()
                .replace('\\', '/') + "=" + hash + System.lineSeparator();

        Files.writeString(
                stagingFile,
                entry,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
        );
    }
}