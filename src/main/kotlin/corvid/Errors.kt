// Errors.kt — the error surface: CorvidException carries the engine's
// error CODE (FFI.md §1.3, frozen per §8), mapped 1:1 onto ErrCode.
//
// The same-call guarantee (PLAN ruling 7): the engine's last-error slot
// is thread-local and JNI native methods run on the calling Java
// thread, so the wrapper reads the code+message immediately after the
// failing call, on the same thread, with nothing (no Cleaners or
// finalizers make corvid calls) able to interpose — the read always
// sees THIS failure's detail.
package corvid

/** The engine's error codes (corvid_err, FFI.md §1.3). 1–18 map 1:1 onto
 *  the engine's `corvid::Error` variants; 19 (BUSY) is FFI-only. */
enum class ErrCode(val value: Int) {
    OK(0),
    DATABASE(1),
    TRANSACTION(2),
    TABLE(3),
    STORAGE(4),
    COMMIT(5),
    SET_DURABILITY(6),
    COMPACTION(7),
    DECODE(8),
    CORRUPT_INDEX(9),
    RESERVED_COLLECTION(10),
    INVALID_NAME(11),
    ARGUMENT(12),
    INCOMPATIBLE_FORMAT(13),
    EMPTY_INDEX_TRAINING(14),
    SCHEMA_VIOLATION(15),
    INVALID_DUMP(16),
    BACKUP_TARGET_EXISTS(17),
    IO(18),
    BUSY(19),
    ;

    companion object {
        fun of(value: Int): ErrCode =
            entries.firstOrNull { it.value == value } ?: OK
    }
}

/** The failure surface of every API call: a checked-in-code CorvidException
 *  carrying the engine's error code and message.
 *
 *      try {
 *          db.open(path)
 *      } catch (e: CorvidException) {
 *          if (e.code == ErrCode.SCHEMA_VIOLATION) { ... }
 *      }
 */
class CorvidException(
    val code: ErrCode,
    message: String,
) : RuntimeException("[${code.value}] $message")
