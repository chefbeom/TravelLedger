package com.playdata.calen.account.service;

import com.playdata.calen.common.exception.BadRequestException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Service;

@Service
public class DefaultSystemCommandRunner implements SystemCommandRunner {

    @Override
    public CommandResult run(List<String> command) {
        Process process = start(command);
        String stdout = readAll(process.getInputStream());
        String stderr = readAll(process.getErrorStream());
        int exitCode = waitFor(process, command);
        return new CommandResult(exitCode, stdout, stderr);
    }

    @Override
    public void runDumpToGzip(List<String> command, Path outputFile) {
        Process process = start(command);
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread stderrThread = copyAsync(process.getErrorStream(), stderr);

        try (InputStream inputStream = process.getInputStream();
             OutputStream fileStream = Files.newOutputStream(outputFile);
             GZIPOutputStream gzipOutputStream = new GZIPOutputStream(fileStream)) {
            inputStream.transferTo(gzipOutputStream);
        } catch (IOException exception) {
            process.destroyForcibly();
            throw new BadRequestException("데이터 백업 파일을 만들지 못했습니다.");
        }

        join(stderrThread);
        int exitCode = waitFor(process, command);
        if (exitCode != 0) {
            throw new BadRequestException(resolveProcessMessage("데이터 백업에 실패했습니다.", stderr.toString(StandardCharsets.UTF_8)));
        }
    }

    @Override
    public void runGzipImport(Path gzipFile, List<String> command) {
        Process process = start(command);
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread stderrThread = copyAsync(process.getErrorStream(), stderr);
        Thread stdoutThread = copyAsync(process.getInputStream(), OutputStream.nullOutputStream());

        try (InputStream fileInput = Files.newInputStream(gzipFile);
             GZIPInputStream gzipInput = new GZIPInputStream(fileInput);
             OutputStream processInput = process.getOutputStream()) {
            gzipInput.transferTo(processInput);
        } catch (IOException exception) {
            process.destroyForcibly();
            throw new BadRequestException("백업 파일을 복구 데이터베이스로 적용하지 못했습니다.");
        }

        join(stdoutThread);
        join(stderrThread);
        int exitCode = waitFor(process, command);
        if (exitCode != 0) {
            throw new BadRequestException(resolveProcessMessage("백업 파일 복구에 실패했습니다.", stderr.toString(StandardCharsets.UTF_8)));
        }
    }

    private Process start(List<String> command) {
        try {
            return new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new BadRequestException("데이터 작업에 필요한 시스템 명령을 실행할 수 없습니다.");
        }
    }

    private int waitFor(Process process, List<String> command) {
        try {
            return process.waitFor();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("데이터 작업이 중단되었습니다.");
        }
    }

    private String readAll(InputStream inputStream) {
        try {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BadRequestException("데이터 작업 결과를 읽지 못했습니다.");
        }
    }

    private Thread copyAsync(InputStream inputStream, OutputStream outputStream) {
        Thread thread = new Thread(() -> {
            try (InputStream source = inputStream; OutputStream target = outputStream) {
                source.transferTo(target);
            } catch (IOException ignored) {
                // The main flow checks the process exit code and surfaces a single user-facing error.
            }
        });
        thread.start();
        return thread;
    }

    private void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("데이터 작업이 중단되었습니다.");
        }
    }

    private String resolveProcessMessage(String fallback, String stderr) {
        String normalized = stderr == null ? "" : stderr.trim();
        return normalized.isEmpty() ? fallback : normalized;
    }
}
