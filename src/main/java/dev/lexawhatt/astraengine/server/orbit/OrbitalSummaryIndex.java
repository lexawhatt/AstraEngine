package dev.lexawhatt.astraengine.server.orbit;

import dev.lexawhatt.astraengine.surface.orbit.OrbitalPage;
import dev.lexawhatt.astraengine.surface.orbit.OrbitalPatch;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Save-owned sparse page directory. Only visited/edited pages exist; fine observations reside in bounded asynchronous pages. */
public final class OrbitalSummaryIndex extends SavedData {
    private final Map<OrbitalPage.Key, OrbitalPatch> pages = new HashMap<>();
    private long revision;
    private final java.util.ArrayList<OrbitalPage.Key> pageOrder = new java.util.ArrayList<>();
    private final Map<OrbitalPage.Key, java.util.BitSet> dirty = new HashMap<>();
    private final java.util.ArrayList<OrbitalPage.Key> dirtyOrder = new java.util.ArrayList<>();
    private final Map<OrbitalPage.Key, Integer> dirtySlots = new HashMap<>();

    private OrbitalSummaryIndex() { }

    /** Requires the logical server thread. Missing derived data starts empty; canonical blocks are never rewritten. */
    public static OrbitalSummaryIndex get(MinecraftServer server) {
        if (server == null || !server.isSameThread()) { throw new IllegalStateException("Orbital index requires the server thread"); }
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(OrbitalSummaryIndex::new,
                (tag, lookup) -> decode(tag), null), "astraengine_orbital_index");
    }

    /** Records derived work durably; canonical chunks are never forced loaded to service a request. */
    public void mark(OrbitalPage.Key key, int chunkX, int chunkZ) {
        var bits = dirty.get(key);
        if (bits == null) {
            bits = new java.util.BitSet(256); dirty.put(key, bits);
            dirtySlots.put(key, dirtyOrder.size()); dirtyOrder.add(key);
        }
        int slot = OrbitalPage.slot(chunkX, chunkZ);
        if (!bits.get(slot)) { bits.set(slot); setDirty(); }
    }
    /** Immutable dirty-page keys, including work interrupted by a previous shutdown. */
    public java.util.List<OrbitalPage.Key> dirtyPages() { return java.util.List.copyOf(dirty.keySet()); }
    /** Constant-time bounded scheduler access; null only when no dirty page remains. */
    public OrbitalPage.Key dirtyKey(int cursor) {
        return dirtyOrder.isEmpty() ? null : dirtyOrder.get(Math.floorMod(cursor, dirtyOrder.size()));
    }
    public int dirtyPageCount() { return dirtyOrder.size(); }
    /** Copy of the pending chunk mask; no caller can mutate stored ownership. */
    public java.util.BitSet dirty(OrbitalPage.Key key) {
        var bits = dirty.get(key); return bits == null ? new java.util.BitSet(256) : (java.util.BitSet) bits.clone();
    }
    /** Retires one successfully observed chunk. Empty masks disappear from persistence. */
    public void captured(OrbitalPage.Key key, int slot) {
        var bits = dirty.get(key);
        if (bits != null && bits.get(slot)) {
            bits.clear(slot);
            if (bits.isEmpty()) {
                dirty.remove(key); int index = dirtySlots.remove(key);
                var last = dirtyOrder.removeLast();
                if (index < dirtyOrder.size()) { dirtyOrder.set(index, last); dirtySlots.put(last, index); }
            }
            setDirty();
        }
    }
    /** Allocates an increasing save-local observation revision. */
    public long nextRevision() { revision = Math.incrementExact(revision); setDirty(); return revision; }
    /** Current sparse pages; immutable values, with a copied collection for caller iteration. */
    public Collection<OrbitalPatch> pages() { return java.util.List.copyOf(pages.values()); }
    /** Number of sparse observed pages, never a count of all theoretical planetary pages. */
    public int pageCount() { return pageOrder.size(); }
    /** At most 1024 immutable directory entries for bounded progressive interest selection. */
    public java.util.List<OrbitalPatch> window(int start, int count) {
        if (count < 0 || count > 1024) { throw new IllegalArgumentException("Orbital directory window exceeds budget"); }
        if (pageOrder.isEmpty()) { return java.util.List.of(); }
        var result = new java.util.ArrayList<OrbitalPatch>(Math.min(count, pageOrder.size()));
        for (int i = 0; i < Math.min(count, pageOrder.size()); i++) {
            result.add(pages.get(pageOrder.get(Math.floorMod(start + i, pageOrder.size()))));
        }
        return java.util.List.copyOf(result);
    }
    /** Reads the current revision of one directory entry without loading its fine page. */
    public OrbitalPatch page(OrbitalPage.Key key) { return pages.get(key); }
    /** Whether a page has been observed before; no page I/O or chunk generation. */
    public boolean contains(OrbitalPage.Key key) { return pages.containsKey(key); }
    /** Updates only the derived page directory. */
    public void update(OrbitalPage page, long value) {
        if (!pages.containsKey(page.key())) { pageOrder.add(page.key()); }
        pages.put(page.key(), page.coarse(value)); revision = Math.max(revision, value); setDirty();
    }

    /** Strict decoding retains version/revision identity; malformed cache data is not partially accepted. */
    public static OrbitalSummaryIndex decode(CompoundTag tag) {
        if (tag == null || !tag.contains("version", Tag.TAG_INT) || tag.getInt("version") != 1
                || !tag.contains("revision", Tag.TAG_LONG) || tag.getLong("revision") < 0
                || !tag.contains("pages", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Invalid orbital directory version or revision");
        }
        var result = new OrbitalSummaryIndex(); result.revision = tag.getLong("revision");
        ListTag pages = tag.getList("pages", Tag.TAG_COMPOUND);
        for (int i = 0; i < pages.size(); i++) {
            var patch = OrbitalSummaryCodec.patch(pages.getCompound(i));
            var key = new OrbitalPage.Key(patch.surface(), patch.x(), patch.z());
            if (patch.level() != 4 || patch.revision() > result.revision || result.pages.put(key, patch) != null) {
                throw new IllegalArgumentException("Invalid or duplicate orbital directory entry");
            }
            result.pageOrder.add(key);
        }
        if (tag.contains("dirty", Tag.TAG_LIST)) {
            ListTag pending = tag.getList("dirty", Tag.TAG_COMPOUND);
            for (int i = 0; i < pending.size(); i++) {
                CompoundTag entry = pending.getCompound(i);
                OrbitalPatch patch = OrbitalSummaryCodec.patch(entry);
                var key = new OrbitalPage.Key(patch.surface(), patch.x(), patch.z());
                long[] mask = entry.getLongArray("pending");
                if (patch.level() != 4 || mask.length > 4 || result.dirty.containsKey(key)) {
                    throw new IllegalArgumentException("Invalid orbital pending page");
                }
                var bits = java.util.BitSet.valueOf(mask);
                if (!bits.isEmpty()) {
                    result.dirty.put(key, bits); result.dirtySlots.put(key, result.dirtyOrder.size()); result.dirtyOrder.add(key);
                }
            }
        }
        return result;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 1); tag.putLong("revision", revision);
        ListTag values = new ListTag();
        pages.values().forEach(value -> values.add(OrbitalSummaryCodec.patch(value)));
        tag.put("pages", values);
        ListTag pending = new ListTag();
        dirty.forEach((key, mask) -> {
            var empty = new OrbitalPatch(key.surface(), key.x(), key.z(), 4, Math.max(1, revision),
                    java.util.Collections.nCopies(16, OrbitalPatch.Cell.EMPTY));
            CompoundTag entry = OrbitalSummaryCodec.patch(empty); entry.putLongArray("pending", mask.toLongArray());
            pending.add(entry);
        });
        tag.put("dirty", pending); return tag;
    }
}
