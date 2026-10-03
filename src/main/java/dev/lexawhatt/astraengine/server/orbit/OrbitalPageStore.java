package dev.lexawhatt.astraengine.server.orbit;

import dev.lexawhatt.astraengine.AstraEngine;
import dev.lexawhatt.astraengine.surface.orbit.OrbitalPage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.Util;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * One server's 64-page LRU of derived observations. At most four host-I/O-pool operations run;
 * tick access never waits for disk. Files are atomically replaced and contain no authoritative block state.
 * Only shutdown waits for already accepted dirty pages. No worker holds a level, player or server reference.
 */
public final class OrbitalPageStore implements AutoCloseable {
    public static final int MAX_RESIDENT_PAGES = 64;
    private static final int MAX_IO = 4;
    private final Path directory;
    private final LinkedHashMap<OrbitalPage.Key, Entry> resident = new LinkedHashMap<>(16, .75f, true);
    private int pending;
    private long tick;
    private boolean closed;
    private List<OrbitalPage.Key> unflushedPages = List.of();

    /** Directory must belong to this server's disposable or canonical world, never an unrelated cache. */
    public OrbitalPageStore(Path directory) {
        if (directory == null) { throw new IllegalArgumentException("Orbital page directory is required"); }
        this.directory = directory;
    }

    /** Polls completed immutable I/O results and schedules bounded writes; caller owns the server thread. */
    public void tick() {
        requireOpen(); tick++;
        for (Entry entry : resident.values()) {
            if (entry.future == null || !entry.future.isDone()) { continue; }
            pending--;
            try {
                var completed = entry.future.join();
                if (entry.writing) {
                    if (entry.page == completed) { entry.dirty = false; }
                } else { entry.page = completed; }
                entry.failed = false;
            } catch (CompletionException exception) {
                entry.retryTick = tick + 200;
                if (!entry.failed) {
                    AstraEngine.LOGGER.error("Orbital derived page I/O failed; canonical chunks remain unchanged", exception.getCause());
                }
                entry.failed = true;
            }
            entry.future = null;
        }
        for (Entry entry : resident.values()) {
            if (pending >= MAX_IO) { break; }
            if (entry.dirty && entry.future == null && tick >= entry.retryTick) { write(entry); }
        }
    }

    /** Returns a ready page or schedules a bounded asynchronous read. Unready never means an empty replacement. */
    public Optional<OrbitalPage> get(OrbitalPage.Key key) {
        requireOpen();
        Entry entry = resident.get(key);
        if (entry != null) {
            if (entry.page == null && entry.future == null && tick >= entry.retryTick && pending < MAX_IO) {
                read(entry, key);
            }
            return Optional.ofNullable(entry.page);
        }
        if (pending >= MAX_IO || !makeRoom()) { return Optional.empty(); }
        entry = new Entry(); resident.put(key, entry); read(entry, key);
        return Optional.empty();
    }

    private void read(Entry entry, OrbitalPage.Key key) {
        Path path = path(key); pending++; entry.writing = false;
        entry.future = CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.exists(path)) { return new OrbitalPage(key, java.util.Map.of()); }
                return OrbitalSummaryCodec.page(NbtIo.readCompressed(path, NbtAccounter.create(512 * 1024)), key);
            } catch (IOException | IllegalArgumentException exception) {
                throw new CompletionException("Could not read orbital page " + path, exception);
            }
        }, Util.ioPool());
    }

    /** Replaces only a ready resident page; accepted mutations persist before clean eviction. */
    public void put(OrbitalPage page) {
        requireOpen();
        Entry entry = resident.get(page.key());
        if (entry == null || entry.page == null) { throw new IllegalStateException("Orbital page has not finished loading"); }
        entry.page = page; entry.dirty = true;
    }

    /** Number of resident pages, including in-flight reads; never exceeds 64. */
    public int residentPages() { return resident.size(); }
    /** True after all accepted changes have reached derived page files. */
    public boolean settled() {
        return unflushedPages.isEmpty() && pending == 0 && resident.values().stream().noneMatch(value -> value.dirty);
    }

    /** Failed shutdown writes, for durable invalidation before the host saves the server's metadata. */
    public List<OrbitalPage.Key> unflushedPages() { return unflushedPages; }

    private boolean makeRoom() {
        if (resident.size() < MAX_RESIDENT_PAGES) { return true; }
        var iterator = resident.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next().getValue();
            if (!entry.dirty && entry.future == null) { iterator.remove(); return true; }
        }
        return false;
    }

    private void write(Entry entry) {
        OrbitalPage value = entry.page; Path path = path(value.key());
        entry.writing = true; pending++;
        entry.future = CompletableFuture.supplyAsync(() -> {
            try { writeFile(value, path); return value; }
            catch (IOException exception) { throw new CompletionException("Could not write orbital page " + path, exception); }
        }, Util.ioPool());
    }

    private static void writeFile(OrbitalPage page, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "page-", ".tmp");
        try {
            NbtIo.writeCompressed(OrbitalSummaryCodec.page(page), temporary);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private Path path(OrbitalPage.Key key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.identity().getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(hash) + ".nbt");
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }

    /** Shutdown-only drain. A failed flush is reported and never represented as persisted success. */
    @Override public void close() {
        if (closed) { return; }
        var failed = new java.util.ArrayList<OrbitalPage.Key>();
        for (Entry entry : List.copyOf(resident.values())) {
            if (entry.future != null) {
                try {
                    var value = entry.future.join();
                    if (!entry.writing) { entry.page = value; }
                    else if (entry.page == value) { entry.dirty = false; }
                } catch (CompletionException exception) {
                    AstraEngine.LOGGER.error("Orbital cache shutdown observed an I/O failure", exception.getCause());
                }
            }
            if (entry.dirty && entry.page != null) {
                OrbitalPage page = entry.page; Path path = path(page.key());
                try {
                    CompletableFuture.runAsync(() -> {
                        try { writeFile(page, path); }
                        catch (IOException exception) { throw new CompletionException(exception); }
                    }, Util.ioPool()).join();
                } catch (CompletionException exception) {
                    failed.add(page.key());
                    AstraEngine.LOGGER.error("Orbital page {} could not flush; its observations require revalidation",
                            page.key().identity(), exception.getCause());
                }
            }
        }
        unflushedPages = List.copyOf(failed); resident.clear(); pending = 0; closed = true;
    }

    private void requireOpen() { if (closed) { throw new IllegalStateException("Orbital page store is closed"); } }
    private static final class Entry {
        private OrbitalPage page;
        private CompletableFuture<OrbitalPage> future;
        private boolean writing;
        private boolean dirty;
        private boolean failed;
        private long retryTick;
    }
}
