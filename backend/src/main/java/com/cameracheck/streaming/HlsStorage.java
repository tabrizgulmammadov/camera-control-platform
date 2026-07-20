package com.cameracheck.streaming;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Temp storage layout for streaming:
 * <ul>
 *   <li>{@code root()} — one sub-directory of HLS output per stream. This tree
 *       (and ONLY this tree) is served statically at /hls/**.</li>
 *   <li>{@code logsRoot()} — per-stream ffmpeg logs. Deliberately OUTSIDE the
 *       served tree: ffmpeg error output can contain the credentialed RTSP URL
 *       and must never be reachable over HTTP.</li>
 * </ul>
 * Stale data from a previous (crashed) run is wiped at startup.
 */
@Component
public class HlsStorage {

    private static final Logger log = LoggerFactory.getLogger(HlsStorage.class);

    private final Path root;
    private final Path logsRoot;

    public HlsStorage() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        this.root = tmp.resolve("camera-check-hls");
        this.logsRoot = tmp.resolve("camera-check-hls-logs");
        cleanStale(root);
        cleanStale(logsRoot);
        try {
            Files.createDirectories(root);
            Files.createDirectories(logsRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create HLS temp directories under " + tmp, e);
        }
    }

    public Path root() {
        return root;
    }

    public Path logsRoot() {
        return logsRoot;
    }

    /** Best-effort removal of leftovers from a previous run. */
    private static void cleanStale(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(dir))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best effort
                        }
                    });
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not clean stale HLS data in {}: {}", dir, e.toString());
        }
    }
}
