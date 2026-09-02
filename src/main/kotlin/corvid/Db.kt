// Db.kt — the database handle: open/close, collections, dump/load,
// backup, compact (FFI.md §4.1/§4.13).
//
// Threading (PLAN ruling 8): Db is safe for concurrent use from
// multiple threads (engine-backed; reads concurrent, writes
// serialized). close() is idempotent; use after close throws.
package corvid

import corvid.jni.Natives

class Db private constructor(internal val h: Long) : AutoCloseable {

    init {
        Corvid.load()
    }

    internal var closed = false
        private set

    /** The collection handle lister's view of this db. */
    fun collection(name: String): Collection {
        ensureOpen()
        val c = Natives.nCollection(h, Values.utf8(name))
        if (c == 0L) Values.throwLastError()
        return Collection(c)
    }

    /** User collection names (engine `__` namespaces excluded), in name
     *  order. Listing creates nothing. */
    fun collections(): List<String> {
        ensureOpen()
        val cursor = Natives.nCollections(h)
        if (cursor == 0L) Values.throwLastError()
        val out = mutableListOf<String>()
        while (true) {
            val b = Natives.nStrsNext(cursor) ?: break
            out.add(String(b, Charsets.UTF_8))
        }
        Natives.nStrsFree(cursor)
        return out
    }

    /** Write a logical, version-stamped dump of the whole database to
     *  `path`, from one read snapshot. */
    fun dump(path: String) {
        ensureOpen()
        Values.check(Natives.nDump(h, Values.utf8(path)))
    }

    /** Replay a dump into this database (merges with pre-existing
     *  collections per the engine contract). */
    fun load(path: String) {
        ensureOpen()
        Values.check(Natives.nLoad(h, Values.utf8(path)))
    }

    /** Replay a dump, renaming every collection occurrence: the
     *  migration path for legacy `__`-containing names. Validated
     *  BEFORE the stream is read — an invalid target fails with
     *  [ErrCode.INVALID_NAME], a colliding mapping with [ErrCode.ARGUMENT]. */
    fun loadWithRenames(path: String, renames: Map<String, String>) {
        ensureOpen()
        val olds = renames.keys.map { Values.utf8(it) }.toTypedArray()
        val news = renames.values.map { Values.utf8(it) }.toTypedArray()
        Values.check(Natives.nLoadWithRenames(h, Values.utf8(path), olds, news))
    }

    /** Consistent point-in-time PHYSICAL backup to a FRESH file at
     *  `path` — an existing target fails with [ErrCode.BACKUP_TARGET_EXISTS].
     *  Physical means feature-configuration-dependent; use dump/load to
     *  move between feature builds. Safe while writers are active. */
    fun backup(path: String) {
        ensureOpen()
        Values.check(Natives.nBackup(h, Values.utf8(path)))
    }

    /** Reclaim file space after heavy deletes — offline maintenance
     *  requiring quiescence: every derived handle must already be
     *  closed (a violation fails with the FFI-only [ErrCode.BUSY]).
     *  Returns whether any data moved; in-memory dbs report false. */
    fun compact(): Boolean {
        ensureOpen()
        val moved = IntArray(1)
        Values.check(Natives.nCompact(h, moved))
        return moved[0] != 0
    }

    /** Release this handle's reference. Dropping the last reference
     *  releases the engine and its file locks; derived handles keep the
     *  engine alive independently. Idempotent (AutoCloseable, so `use`
     *  works). */
    override fun close() {
        if (closed) return
        closed = true
        Values.check(Natives.nClose(h))
    }

    private fun ensureOpen() {
        check(!closed) { "corvid: Db is closed" }
    }

    internal companion object {
        internal fun open(h: Long): Db {
            if (h == 0L) Values.throwLastError()
            return Db(h)
        }
    }
}

/** Open (creating if absent) a file-backed database at `path`. */
fun open(path: String): Db {
    Corvid.load()
    return Db.open(Natives.nOpen(Values.utf8(path)))
}

/** A purely in-memory database (no file). */
fun openMemory(): Db {
    Corvid.load()
    return Db.open(Natives.nOpenMemory())
}
