package com.playdata.calen.account.service;

import java.nio.file.Path;
import java.util.List;

public interface SystemCommandRunner {

    CommandResult run(List<String> command);

    void runDumpToGzip(List<String> command, Path outputFile);

    void runGzipImport(Path gzipFile, List<String> command);
}
