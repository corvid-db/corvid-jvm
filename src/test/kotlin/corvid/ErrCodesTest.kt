// ErrCodesTest.kt — pins the frozen error-code table (FFI.md §1.3:
// values are never renumbered) — the docs/SURFACE.tsv mapping for the
// engine's corvid::Error rows. The fixtures prove the codes the suite
// can trigger (10/11/12/14/15/17); the redb-internal fault variants
// have no public trigger (the engine's own radar exempts them), so the
// table itself is the proof that every variant maps to its documented
// code. Code 19 (BUSY) is FFI-only: compact exclusivity, with no
// engine Error variant.
package corvid

import kotlin.test.Test
import kotlin.test.assertEquals

class ErrCodesTest {

    @Test
    fun errorCodeTableIsFrozen() {
        val frozen = listOf(
            ErrCode.OK to 0,
            ErrCode.DATABASE to 1,
            ErrCode.TRANSACTION to 2,
            ErrCode.TABLE to 3,
            ErrCode.STORAGE to 4,
            ErrCode.COMMIT to 5,
            ErrCode.SET_DURABILITY to 6,
            ErrCode.COMPACTION to 7,
            ErrCode.DECODE to 8,
            ErrCode.CORRUPT_INDEX to 9,
            ErrCode.RESERVED_COLLECTION to 10,
            ErrCode.INVALID_NAME to 11,
            ErrCode.ARGUMENT to 12,
            ErrCode.INCOMPATIBLE_FORMAT to 13,
            ErrCode.EMPTY_INDEX_TRAINING to 14,
            ErrCode.SCHEMA_VIOLATION to 15,
            ErrCode.INVALID_DUMP to 16,
            ErrCode.BACKUP_TARGET_EXISTS to 17,
            ErrCode.IO to 18,
            ErrCode.BUSY to 19,
        )
        assertEquals(20, ErrCode.entries.size, "table must hold exactly the 20 frozen codes (0..19)")
        for ((code, want) in frozen) {
            assertEquals(want, code.value, "$code = ${code.value}, want $want (frozen table drifted)")
        }
        // The reverse lookup used by the exception surface.
        for ((code, _) in frozen) {
            assertEquals(code, ErrCode.of(code.value), "ErrCode.of(${code.value}) must round-trip $code")
        }
    }
}
