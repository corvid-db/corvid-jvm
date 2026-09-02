/* corvid_jni.c — the JNI shim of corvid-jvm, the Kotlin/JVM binding for
 * the `corvid` embedded database (corvid-db/corvid, MIT).
 *
 * One C file, no business logic: it marshals between the engine's typed
 * C ABI (the fetched, pinned corvid.h + cdylib under deps/current) and
 * the JVM, following the JNI discipline ruled in docs/PLAN.md:
 *
 *   - No raw pointer ever reaches Kotlin: handles cross as jlong and
 *     live only inside corvid.jni.Natives (internal) + the wrappers.
 *   - Borrowed data (rows/geohits/strs items, _ref views, callback
 *     arguments) is COPIED into JVM-owned memory inside the same native
 *     call that observed it; nothing borrowed is retained past return.
 *   - Documents decode recursively in C — ONE crossing per row — with
 *     PushLocalFrame/PopLocalFrame bounding the local-reference table
 *     and DeleteLocalRef hygiene in loops. Every local ref is either
 *     returned, deleted, or scoped by a frame; the only references that
 *     outlive a call are the globals cached in JNI_OnLoad (the UTF_8
 *     charset and java/lang/Object).
 *   - Strings cross as REAL UTF-8 bytes (String.getBytes(UTF_8) / new
 *     String(bytes, UTF_8)), never JNI's modified-UTF-8 jstring
 *     functions, on the engine side (FFI.md §1.5).
 *   - The §1.6 callbacks (scan sink, update closure) run synchronously
 *     on the calling thread inside this frame, so the jobject argument
 *     stays a valid local ref for the whole call — no other global refs
 *     are retained anywhere. An exception thrown by a Kotlin callback
 *     ABORTS the engine call (§1.6 abort channel: stop the scan /
 *     CORVID_ERR the update) and is then left PENDING so the JVM
 *     rethrows it at the call site — corvid's go-binding ruling
 *     (recover-and-repanic), JNI-shaped. It never unwinds through C.
 *   - Consumption is unconditional (FFI.md §8): the Kotlin layer marks
 *     its side consumed whatever the status; this file frees owned
 *     values exactly once on every path.
 */

#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "corvid.h"

/* ------------------------------------------------------------------ */
/* Cached VM references (JNI_OnLoad)                                   */
/* ------------------------------------------------------------------ */

static jobject g_utf8_charset;   /* global ref — retained */
static jclass g_object_class;    /* global ref — retained (NewObjectArray) */

static jmethodID g_string_get_bytes;   /* String.getBytes(Charset)[B */
static jmethodID g_string_ctor_utf8;   /* new String(byte[], Charset) */
static jmethodID g_boolean_valueof;    /* Boolean.valueOf(Z) */
static jmethodID g_long_valueof;       /* Long.valueOf(J) */
static jmethodID g_double_valueof;     /* Double.valueOf(D) */
static jmethodID g_float_valueof;      /* Float.valueOf(F) */
static jmethodID g_integer_valueof;    /* Integer.valueOf(I) */
static jmethodID g_number_longvalue;   /* Number.longValue()J */
static jmethodID g_number_doublevalue; /* Number.doubleValue()D */
static jmethodID g_boolean_boolvalue;  /* Boolean.booleanValue()Z */
static jmethodID g_arraylist_ctor;     /* new ArrayList() */
static jmethodID g_list_size;          /* List.size()I */
static jmethodID g_list_get;           /* List.get(I)Object */
static jmethodID g_list_add;           /* List.add(Object)Z */
static jmethodID g_linkedhashmap_ctor; /* new LinkedHashMap() */
static jmethodID g_map_put;            /* Map.put(Object,Object)Object */
static jmethodID g_map_entryset;       /* Map.entrySet()Set */
static jmethodID g_set_iterator;       /* Set.iterator()Iterator */
static jmethodID g_iterator_hasnext;   /* Iterator.hasNext()Z */
static jmethodID g_iterator_next;      /* Iterator.next()Object */
static jmethodID g_entry_getkey;       /* Map.Entry.getKey()Object */
static jmethodID g_entry_getvalue;     /* Map.Entry.getValue()Object */

/* One-time caching. Any miss refuses to load the library (JNI_ERR)
 * rather than failing call by call later. */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK)
        return JNI_ERR;

#define CACHE_GLOBAL(var, clsName, fieldName, sig)                     \
    do {                                                               \
        jclass c = (*env)->FindClass(env, clsName);                    \
        if (c == NULL) return JNI_ERR;                                 \
        jfieldID f = (*env)->GetStaticFieldID(env, c, fieldName, sig); \
        if (f == NULL) { (*env)->DeleteLocalRef(env, c); return JNI_ERR; } \
        jobject o = (*env)->GetStaticObjectField(env, c, f);           \
        (*env)->DeleteLocalRef(env, c);                                \
        if (o == NULL) return JNI_ERR;                                 \
        var = (*env)->NewGlobalRef(env, o);                            \
        (*env)->DeleteLocalRef(env, o);                                \
        if (var == NULL) return JNI_ERR;                               \
    } while (0)

#define CACHE_STATIC(clsName, name, sig, out)                          \
    do {                                                               \
        jclass c = (*env)->FindClass(env, clsName);                    \
        if (c == NULL) return JNI_ERR;                                 \
        out = (*env)->GetStaticMethodID(env, c, name, sig);            \
        (*env)->DeleteLocalRef(env, c);                                \
        if (out == NULL) return JNI_ERR;                               \
    } while (0)

#define CACHE(clsName, name, sig, out)                                 \
    do {                                                               \
        jclass c = (*env)->FindClass(env, clsName);                    \
        if (c == NULL) return JNI_ERR;                                 \
        out = (*env)->GetMethodID(env, c, name, sig);                  \
        (*env)->DeleteLocalRef(env, c);                                \
        if (out == NULL) return JNI_ERR;                               \
    } while (0)

    CACHE_GLOBAL(g_utf8_charset, "java/nio/charset/StandardCharsets", "UTF_8",
                 "Ljava/nio/charset/Charset;");
    {
        jclass oc = (*env)->FindClass(env, "java/lang/Object");
        if (oc == NULL) return JNI_ERR;
        jobject og = (*env)->NewGlobalRef(env, oc);
        (*env)->DeleteLocalRef(env, oc);
        if (og == NULL) return JNI_ERR;
        g_object_class = og;
    }

    CACHE("java/lang/String", "getBytes", "(Ljava/nio/charset/Charset;)[B",
          g_string_get_bytes);
    CACHE("java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V",
          g_string_ctor_utf8);
    CACHE_STATIC("java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;",
                 g_boolean_valueof);
    CACHE_STATIC("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;",
                 g_long_valueof);
    CACHE_STATIC("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;",
                 g_double_valueof);
    CACHE_STATIC("java/lang/Float", "valueOf", "(F)Ljava/lang/Float;",
                 g_float_valueof);
    CACHE_STATIC("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;",
                 g_integer_valueof);
    CACHE("java/lang/Number", "longValue", "()J", g_number_longvalue);
    CACHE("java/lang/Number", "doubleValue", "()D", g_number_doublevalue);
    CACHE("java/lang/Boolean", "booleanValue", "()Z", g_boolean_boolvalue);
    CACHE("java/util/ArrayList", "<init>", "()V", g_arraylist_ctor);
    CACHE("java/util/List", "size", "()I", g_list_size);
    CACHE("java/util/List", "get", "(I)Ljava/lang/Object;", g_list_get);
    CACHE("java/util/List", "add", "(Ljava/lang/Object;)Z", g_list_add);
    CACHE("java/util/LinkedHashMap", "<init>", "()V", g_linkedhashmap_ctor);
    CACHE("java/util/Map", "put",
          "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", g_map_put);
    CACHE("java/util/Map", "entrySet", "()Ljava/util/Set;", g_map_entryset);
    CACHE("java/util/Set", "iterator", "()Ljava/util/Iterator;", g_set_iterator);
    CACHE("java/util/Iterator", "hasNext", "()Z", g_iterator_hasnext);
    CACHE("java/util/Iterator", "next", "()Ljava/lang/Object;", g_iterator_next);
    CACHE("java/util/Map$Entry", "getKey", "()Ljava/lang/Object;", g_entry_getkey);
    CACHE("java/util/Map$Entry", "getValue", "()Ljava/lang/Object;",
          g_entry_getvalue);

    return JNI_VERSION_1_8;
}

/* ------------------------------------------------------------------ */
/* Small marshaling helpers                                            */
/* ------------------------------------------------------------------ */

/* The §1.5 empty shape: a non-NULL sentinel for (pointer, 0). */
static const uint8_t g_empty_byte;

static void *xmalloc_null(JNIEnv *env, size_t n) {
    void *p = malloc(n > 0 ? n : 1);
    if (p == NULL)
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/OutOfMemoryError"),
                         "corvid: native buffer");
    return p;
}

/* bytes → fresh jbyteArray (copy; borrowed data crosses as a copy). */
static jbyteArray bytes_to_jbytes(JNIEnv *env, const void *p, size_t n) {
    jbyteArray a = (*env)->NewByteArray(env, (jsize)n);
    if (a == NULL) return NULL; /* OOME pending */
    if (n > 0)
        (*env)->SetByteArrayRegion(env, a, 0, (jsize)n, (const jbyte *)p);
    return a;
}

/* UTF-8 bytes → jstring via new String(bytes, UTF_8) — real UTF-8, not
 * JNI's modified UTF-8 (docs/PLAN.md ruling 5). */
static jstring utf8_to_jstring(JNIEnv *env, const char *p, size_t n) {
    jbyteArray bytes = bytes_to_jbytes(env, p, n);
    if (bytes == NULL) return NULL;
    jclass sc = (*env)->FindClass(env, "java/lang/String");
    if (sc == NULL) { (*env)->DeleteLocalRef(env, bytes); return NULL; }
    jstring s = (*env)->NewObject(env, sc, g_string_ctor_utf8, bytes,
                                  g_utf8_charset);
    (*env)->DeleteLocalRef(env, sc);
    (*env)->DeleteLocalRef(env, bytes);
    return s;
}

/* jstring → malloc'd UTF-8 copy (free() it; never NULL; may be empty).
 * The empty sentinel keeps §1.5's non-NULL shape. Returns 0 on failure
 * (exception pending). */
static int string_to_utf8_copy(JNIEnv *env, jstring s, char **out, size_t *len) {
    if (s == NULL) { *out = (char *)&g_empty_byte; *len = 0; return 1; }
    jbyteArray bytes = (*env)->CallObjectMethod(env, s, g_string_get_bytes,
                                                g_utf8_charset);
    if (bytes == NULL) return 0;
    jsize n = (*env)->GetArrayLength(env, bytes);
    char *buf = (char *)xmalloc_null(env, (size_t)n + 1);
    if (buf == NULL) { (*env)->DeleteLocalRef(env, bytes); return 0; }
    if (n > 0) {
        jbyte *p = (*env)->GetByteArrayElements(env, bytes, NULL);
        if (p == NULL) {
            free(buf);
            (*env)->DeleteLocalRef(env, bytes);
            return 0;
        }
        memcpy(buf, p, (size_t)n);
        (*env)->ReleaseByteArrayElements(env, bytes, p, JNI_ABORT);
    }
    buf[n] = 0;
    (*env)->DeleteLocalRef(env, bytes);
    *out = buf;
    *len = (size_t)n;
    return 1;
}

/* jbyteArray → malloc'd copy (free() it; NULL array = empty sentinel).
 * Returns 0 on failure (exception pending). */
static int jbytes_to_copy(JNIEnv *env, jbyteArray a, const uint8_t **out,
                          size_t *len) {
    if (a == NULL) { *out = &g_empty_byte; *len = 0; return 1; }
    jsize n = (*env)->GetArrayLength(env, a);
    uint8_t *buf = (uint8_t *)xmalloc_null(env, (size_t)n + 1);
    if (buf == NULL) return 0;
    if (n > 0) {
        jbyte *p = (*env)->GetByteArrayElements(env, a, NULL);
        if (p == NULL) { free(buf); return 0; }
        memcpy(buf, p, (size_t)n);
        (*env)->ReleaseByteArrayElements(env, a, p, JNI_ABORT);
    }
    buf[n] = 0;
    *out = buf;
    *len = (size_t)n;
    return 1;
}

/* Out-param writers (Kotlin passes preallocated [1] arrays). */
static void put_int(JNIEnv *env, jintArray a, jint v) {
    if (a == NULL) return;
    (*env)->SetIntArrayRegion(env, a, 0, 1, &v);
}
static void put_long(JNIEnv *env, jlongArray a, jlong v) {
    if (a == NULL) return;
    (*env)->SetLongArrayRegion(env, a, 0, 1, &v);
}
static void put_double(JNIEnv *env, jdoubleArray a, jdouble v) {
    if (a == NULL) return;
    (*env)->SetDoubleArrayRegion(env, a, 0, 1, &v);
}

/* ------------------------------------------------------------------ */
/* Document decode: corvid_value → JVM objects (one crossing)          */
/* ------------------------------------------------------------------ */

/* Recursive decode of a (borrowed or owned) value into fully JVM-owned
 * objects. Frames bound the local refs; loops delete their temporaries.
 * Returns NULL on failure or for the Null value — callers that need to
 * distinguish use corvid_value_type first (the harness does). */
static jobject decode_value(JNIEnv *env, const corvid_value *v) {
    if (v == NULL) return NULL;
    if ((*env)->PushLocalFrame(env, 32) != 0) return NULL; /* OOME pending */
    jobject result = NULL;
    switch (corvid_value_type(v)) {
    case CORVID_TYPE_NULL:
        result = NULL;
        break;
    case CORVID_TYPE_BOOL: {
        int ok = 0;
        int b = corvid_value_as_bool(v, &ok);
        jclass c = (*env)->FindClass(env, "java/lang/Boolean");
        if (c == NULL) break;
        result = (*env)->CallStaticObjectMethod(env, c, g_boolean_valueof,
                                                (jboolean)(b ? JNI_TRUE : JNI_FALSE));
        (*env)->DeleteLocalRef(env, c);
        break;
    }
    case CORVID_TYPE_INT: {
        int ok = 0;
        int64_t i = corvid_value_as_int(v, &ok);
        jclass c = (*env)->FindClass(env, "java/lang/Long");
        if (c == NULL) break;
        result = (*env)->CallStaticObjectMethod(env, c, g_long_valueof, (jlong)i);
        (*env)->DeleteLocalRef(env, c);
        break;
    }
    case CORVID_TYPE_FLOAT: {
        int ok = 0;
        double d = corvid_value_as_float(v, &ok);
        jclass c = (*env)->FindClass(env, "java/lang/Double");
        if (c == NULL) break;
        result = (*env)->CallStaticObjectMethod(env, c, g_double_valueof, d);
        (*env)->DeleteLocalRef(env, c);
        break;
    }
    case CORVID_TYPE_TEXT: {
        size_t n = 0;
        const char *p = corvid_value_text_ref(v, &n);
        result = utf8_to_jstring(env, p ? p : (const char *)&g_empty_byte, n);
        break;
    }
    case CORVID_TYPE_BYTES: {
        size_t n = 0;
        const uint8_t *p = corvid_value_bytes_ref(v, &n);
        result = bytes_to_jbytes(env, p ? p : &g_empty_byte, n);
        break;
    }
    case CORVID_TYPE_VECTOR: {
        size_t dim = 0;
        const float *p = corvid_value_vector_ref(v, &dim);
        jfloatArray a = (*env)->NewFloatArray(env, (jsize)dim);
        if (a == NULL) break;
        if (dim > 0 && p != NULL)
            (*env)->SetFloatArrayRegion(env, a, 0, (jsize)dim, p);
        result = a;
        break;
    }
    case CORVID_TYPE_ARRAY: {
        size_t n = corvid_value_len(v);
        jclass c = (*env)->FindClass(env, "java/util/ArrayList");
        if (c == NULL) break;
        result = (*env)->NewObject(env, c, g_arraylist_ctor);
        (*env)->DeleteLocalRef(env, c);
        if (result == NULL) break;
        for (size_t i = 0; i < n; i++) {
            jobject item = decode_value(env, corvid_value_array_get(v, i));
            if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
            (*env)->CallBooleanMethod(env, result, g_list_add, item);
            if (item != NULL) (*env)->DeleteLocalRef(env, item);
            if ((*env)->ExceptionCheck(env)) { result = NULL; goto done; }
        }
        break;
    }
    case CORVID_TYPE_MAP: {
        size_t n = corvid_value_len(v);
        jclass c = (*env)->FindClass(env, "java/util/LinkedHashMap");
        if (c == NULL) break;
        result = (*env)->NewObject(env, c, g_linkedhashmap_ctor);
        (*env)->DeleteLocalRef(env, c);
        if (result == NULL) break;
        if (n == 0) break;
        /* Keys enumerate through the REAL §4.4 iterator (ascending
         * key-byte order): a decoded map is always complete, whatever
         * wrote the data. */
        corvid_strs *keys = corvid_value_map_keys(v);
        if (keys == NULL) { result = NULL; goto done; }
        for (;;) {
            const char *k = NULL;
            size_t kl = 0;
            if (corvid_strs_next(keys, &k, &kl) != 1) break;
            jstring jk = utf8_to_jstring(env, k ? k : (const char *)&g_empty_byte, kl);
            if (jk == NULL) {
                corvid_strs_free(keys);
                result = NULL;
                goto done;
            }
            const corvid_value *child = corvid_value_map_get(v, k, kl);
            jobject jv = decode_value(env, child);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->DeleteLocalRef(env, jk);
                corvid_strs_free(keys);
                result = NULL;
                goto done;
            }
            /* LinkedHashMap.put keeps insertion (engine iteration)
             * order; the returned previous value is always null here. */
            (*env)->CallObjectMethod(env, result, g_map_put, jk, jv);
            (*env)->DeleteLocalRef(env, jk);
            if (jv != NULL) (*env)->DeleteLocalRef(env, jv);
            if ((*env)->ExceptionCheck(env)) {
                corvid_strs_free(keys);
                result = NULL;
                goto done;
            }
        }
        corvid_strs_free(keys);
        break;
    }
    default:
        result = NULL;
        break;
    }
done:
    return (*env)->PopLocalFrame(env, result);
}

/* ------------------------------------------------------------------ */
/* Document encode: JVM objects → owned corvid_value                  */
/* ------------------------------------------------------------------ */

/* Recursive encode. Returns an OWNED value the caller frees (or that an
 * engine call consumes); NULL on failure with an exception pending.
 * Push consumes unconditionally, including on failure — on sub-failure
 * the partially built container is freed whole (its children were
 * already consumed into it). */
static corvid_value *encode_value(JNIEnv *env, jobject v);

static corvid_value *encode_list(JNIEnv *env, jobject list) {
    corvid_value *arr = corvid_value_array_new();
    if (arr == NULL) return NULL;
    jint n = (*env)->CallIntMethod(env, list, g_list_size);
    if ((*env)->ExceptionCheck(env)) { corvid_value_free(arr); return NULL; }
    for (jint i = 0; i < n; i++) {
        jobject item = (*env)->CallObjectMethod(env, list, g_list_get, i);
        if ((*env)->ExceptionCheck(env)) { corvid_value_free(arr); return NULL; }
        corvid_value *cv = encode_value(env, item);
        if (item != NULL) (*env)->DeleteLocalRef(env, item);
        if (cv == NULL) { corvid_value_free(arr); return NULL; }
        if (corvid_value_array_push(arr, cv) != CORVID_OK) {
            corvid_value_free(arr); /* cv consumed by the push (§8) */
            return NULL;
        }
    }
    return arr;
}

static corvid_value *encode_map(JNIEnv *env, jobject map) {
    corvid_value *m = corvid_value_map_new();
    if (m == NULL) return NULL;
    jobject entries = (*env)->CallObjectMethod(env, map, g_map_entryset);
    if ((*env)->ExceptionCheck(env) || entries == NULL) {
        corvid_value_free(m);
        return NULL;
    }
    jobject it = (*env)->CallObjectMethod(env, entries, g_set_iterator);
    (*env)->DeleteLocalRef(env, entries);
    if ((*env)->ExceptionCheck(env) || it == NULL) {
        corvid_value_free(m);
        return NULL;
    }
    while ((*env)->CallBooleanMethod(env, it, g_iterator_hasnext) == JNI_TRUE) {
        if ((*env)->ExceptionCheck(env)) goto fail;
        jobject entry = (*env)->CallObjectMethod(env, it, g_iterator_next);
        if ((*env)->ExceptionCheck(env) || entry == NULL) goto fail;
        jobject k = (*env)->CallObjectMethod(env, entry, g_entry_getkey);
        jobject val = (*env)->CallObjectMethod(env, entry, g_entry_getvalue);
        (*env)->DeleteLocalRef(env, entry);
        if ((*env)->ExceptionCheck(env)) {
            if (k != NULL) (*env)->DeleteLocalRef(env, k);
            if (val != NULL) (*env)->DeleteLocalRef(env, val);
            goto fail;
        }
        if (k == NULL) {
            if (val != NULL) (*env)->DeleteLocalRef(env, val);
            (*env)->ThrowNew(env,
                (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
                "corvid: map keys must be Strings (got null)");
            goto fail;
        }
        jclass strCls = (*env)->FindClass(env, "java/lang/String");
        if (strCls == NULL || !(*env)->IsInstanceOf(env, k, strCls)) {
            if (strCls != NULL) (*env)->DeleteLocalRef(env, strCls);
            (*env)->DeleteLocalRef(env, k);
            if (val != NULL) (*env)->DeleteLocalRef(env, val);
            (*env)->ThrowNew(env,
                (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
                "corvid: map keys must be Strings");
            goto fail;
        }
        (*env)->DeleteLocalRef(env, strCls);
        char *kbuf = NULL;
        size_t klen = 0;
        int ok = string_to_utf8_copy(env, (jstring)k, &kbuf, &klen);
        (*env)->DeleteLocalRef(env, k);
        if (!ok) {
            if (val != NULL) (*env)->DeleteLocalRef(env, val);
            goto fail;
        }
        corvid_value *cv = encode_value(env, val);
        if (val != NULL) (*env)->DeleteLocalRef(env, val);
        if (cv == NULL) { free(kbuf); goto fail; }
        corvid_status st = corvid_value_map_put(m, kbuf, klen, cv); /* consumes cv */
        free(kbuf);
        if (st != CORVID_OK) { corvid_value_free(m); return NULL; }
    }
    if ((*env)->ExceptionCheck(env)) goto fail;
    (*env)->DeleteLocalRef(env, it);
    return m;
fail:
    (*env)->DeleteLocalRef(env, it);
    corvid_value_free(m);
    return NULL;
}

static corvid_value *encode_value(JNIEnv *env, jobject v) {
    if (v == NULL) return corvid_value_null();

    jclass cls = (*env)->GetObjectClass(env, v);
    if (cls == NULL) return NULL;

    corvid_value *out = NULL;
    jboolean isBool = JNI_FALSE, isLong = JNI_FALSE, isInt = JNI_FALSE;
    jboolean isShort = JNI_FALSE, isByte = JNI_FALSE, isDouble = JNI_FALSE;
    jboolean isFloat = JNI_FALSE, isString = JNI_FALSE, isList = JNI_FALSE;
    jboolean isMap = JNI_FALSE, isBytes = JNI_FALSE, isFloats = JNI_FALSE;

    {
        jclass c;
        if ((c = (*env)->FindClass(env, "java/lang/Boolean")) != NULL) {
            isBool = (*env)->IsInstanceOf(env, v, c);
            (*env)->DeleteLocalRef(env, c);
        }
        if (!isBool && (c = (*env)->FindClass(env, "java/lang/Long")) != NULL) {
            isLong = (*env)->IsInstanceOf(env, v, c);
            (*env)->DeleteLocalRef(env, c);
        }
        if (!isBool && !isLong &&
            (c = (*env)->FindClass(env, "java/lang/Integer")) != NULL) {
            isInt = (*env)->IsInstanceOf(env, v, c);
            (*env)->DeleteLocalRef(env, c);
        }
        if (!isBool && !isLong && !isInt &&
            (c = (*env)->FindClass(env, "java/lang/Short")) != NULL) {
            isShort = (*env)->IsInstanceOf(env, v, c);
            (*env)->DeleteLocalRef(env, c);
        }
        if (!isBool && !isLong && !isInt && !isShort &&
            (c = (*env)->FindClass(env, "java/lang/Byte")) != NULL) {
            isByte = (*env)->IsInstanceOf(env, v, c);
            (*env)->DeleteLocalRef(env, c);
        }
        if (!isBool && !isLong && !isInt && !isShort && !isByte &&
            (c = (*env)->FindClass(env, "java/lang/Double")) != NULL) {
            isDouble = (*env)->IsInstanceOf(env, v, c);
            (*env)->DeleteLocalRef(env, c);
        }
        if (!isBool && !isLong && !isInt && !isShort && !isByte && !isDouble &&
            (c = (*env)->FindClass(env, "java/lang/Float")) != NULL) {
            isFloat = (*env)->IsInstanceOf(env, v, c);
            (*env)->DeleteLocalRef(env, c);
        }
        if (!isBool && !isLong && !isInt && !isShort && !isByte && !isDouble &&
            !isFloat && (c = (*env)->FindClass(env, "java/lang/String")) != NULL) {
            isString = (*env)->IsInstanceOf(env, v, c);
            (*env)->DeleteLocalRef(env, c);
        }
        if (!isString && (c = (*env)->FindClass(env, "[B")) != NULL) {
            isBytes = (*env)->IsInstanceOf(env, v, c);
            (*env)->DeleteLocalRef(env, c);
        }
        if (!isBytes && (c = (*env)->FindClass(env, "[F")) != NULL) {
            isFloats = (*env)->IsInstanceOf(env, v, c);
            (*env)->DeleteLocalRef(env, c);
        }
        if (!isBytes && !isFloats &&
            (c = (*env)->FindClass(env, "java/util/List")) != NULL) {
            isList = (*env)->IsInstanceOf(env, v, c);
            (*env)->DeleteLocalRef(env, c);
        }
        if (!isList && !isBytes && !isFloats &&
            (c = (*env)->FindClass(env, "java/util/Map")) != NULL) {
            isMap = (*env)->IsInstanceOf(env, v, c);
            (*env)->DeleteLocalRef(env, c);
        }
    }

    if ((*env)->ExceptionCheck(env)) { out = NULL; }
    else if (isBool) {
        jboolean b = (*env)->CallBooleanMethod(env, v, g_boolean_boolvalue);
        out = corvid_value_bool(b ? 1 : 0);
    } else if (isLong || isInt || isShort || isByte) {
        jlong n = (*env)->CallLongMethod(env, v, g_number_longvalue);
        out = corvid_value_int((int64_t)n);
    } else if (isDouble || isFloat) {
        jdouble d = (*env)->CallDoubleMethod(env, v, g_number_doublevalue);
        out = corvid_value_float((double)d);
    } else if (isString) {
        char *buf = NULL;
        size_t len = 0;
        if (string_to_utf8_copy(env, (jstring)v, &buf, &len)) {
            out = corvid_value_text(buf, len);
            free(buf);
        }
    } else if (isBytes) {
        const uint8_t *buf = NULL;
        size_t len = 0;
        if (jbytes_to_copy(env, (jbyteArray)v, &buf, &len)) {
            out = corvid_value_bytes(buf, len);
            free((void *)(uintptr_t)buf);
        }
    } else if (isFloats) {
        jfloatArray fa = (jfloatArray)v;
        jsize n = (*env)->GetArrayLength(env, fa);
        if (n > 0) {
            jfloat *p = (*env)->GetFloatArrayElements(env, fa, NULL);
            if (p == NULL) {
                out = NULL;
            } else {
                out = corvid_value_vector((const float *)p, (size_t)n);
                (*env)->ReleaseFloatArrayElements(env, fa, p, JNI_ABORT);
            }
        } else {
            out = corvid_value_vector((const float *)&g_empty_byte, 0);
        }
    } else if (isList) {
        out = encode_list(env, v);
    } else if (isMap) {
        out = encode_map(env, v);
    } else {
        (*env)->ThrowNew(env,
            (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
            "corvid: unsupported value type for a corvid document "
            "(supported: null, Boolean, Long/Int/Short/Byte, Double/Float, "
            "String, ByteArray, FloatArray, List, Map)");
        out = NULL;
    }

    (*env)->DeleteLocalRef(env, cls);
    return out;
}

/* ------------------------------------------------------------------ */
/* The §1.6 callback bridges                                           */
/* ------------------------------------------------------------------ */

typedef struct {
    JNIEnv *env;
    jobject fn;     /* local ref, valid for the whole native call */
    jmethodID mid;  /* its cached `invoke` */
} cb_ctx;

static int scan_bridge(void *ctx, const uint8_t *key, size_t key_len,
                       const corvid_value *doc) {
    cb_ctx *c = (cb_ctx *)ctx;
    JNIEnv *env = c->env;
    jbyteArray jkey = bytes_to_jbytes(env, key, key_len);
    if (jkey == NULL) return 0; /* OOME pending: stop the scan */
    jobject jdoc = decode_value(env, doc);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->DeleteLocalRef(env, jkey);
        return 0; /* pending user exception: stop; it surfaces at the call site */
    }
    jboolean cont = (*env)->CallBooleanMethod(env, c->fn, c->mid, jkey, jdoc);
    (*env)->DeleteLocalRef(env, jkey);
    if (jdoc != NULL) (*env)->DeleteLocalRef(env, jdoc);
    if ((*env)->ExceptionCheck(env)) return 0; /* user exception: stop */
    return cont == JNI_TRUE ? 1 : 0;
}

static corvid_status update_bridge(void *ctx, const corvid_value *current,
                                   corvid_value **out) {
    cb_ctx *c = (cb_ctx *)ctx;
    JNIEnv *env = c->env;
    *out = NULL;
    jobject jcur = current != NULL ? decode_value(env, current) : NULL;
    if ((*env)->ExceptionCheck(env)) return CORVID_ERR; /* abort; exception surfaces */
    jobject jres = (*env)->CallObjectMethod(env, c->fn, c->mid, jcur);
    if (jcur != NULL) (*env)->DeleteLocalRef(env, jcur);
    if ((*env)->ExceptionCheck(env)) return CORVID_ERR; /* abort; exception surfaces */
    if (jres == NULL) return CORVID_OK; /* null replacement ⇒ delete the key */
    corvid_value *v = encode_value(env, jres);
    (*env)->DeleteLocalRef(env, jres);
    if (v == NULL) return CORVID_ERR; /* abort; exception surfaces */
    *out = v;
    return CORVID_OK;
}

/* Resolve the `invoke` method of a Kotlin fun-interface callback. */
static jmethodID cb_invoke(JNIEnv *env, jobject fn, const char *sig) {
    jclass cls = (*env)->GetObjectClass(env, fn);
    if (cls == NULL) return NULL;
    jmethodID mid = (*env)->GetMethodID(env, cls, "invoke", sig);
    (*env)->DeleteLocalRef(env, cls);
    return mid;
}

/* ------------------------------------------------------------------ */
/* corvid.jni.Natives — the native methods                             */
/* ------------------------------------------------------------------ */

#define JNI_NAME(name) Java_corvid_jni_Natives_##name

/* ---- version + errors (§4.1) ---- */

JNIEXPORT jint JNICALL JNI_NAME(nFfiVersion)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jint)corvid_ffi_version();
}

JNIEXPORT jint JNICALL JNI_NAME(nLastErrorCode)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jint)corvid_last_error_code();
}

JNIEXPORT jbyteArray JNICALL JNI_NAME(nLastErrorMessage)(JNIEnv *env, jclass cls) {
    (void)cls;
    size_t len = 0;
    const char *msg = corvid_last_error_message(&len);
    if (msg == NULL) return NULL;
    return bytes_to_jbytes(env, msg, len);
}

/* ---- value construction (§4.3) ---- */

JNIEXPORT jlong JNICALL JNI_NAME(nValueNull)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)corvid_value_null();
}

JNIEXPORT jlong JNICALL JNI_NAME(nValueBool)(JNIEnv *env, jclass cls, jboolean v) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)corvid_value_bool(v ? 1 : 0);
}

JNIEXPORT jlong JNICALL JNI_NAME(nValueInt)(JNIEnv *env, jclass cls, jlong v) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)corvid_value_int((int64_t)v);
}

JNIEXPORT jlong JNICALL JNI_NAME(nValueFloat)(JNIEnv *env, jclass cls, jdouble v) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)corvid_value_float((double)v);
}

JNIEXPORT jlong JNICALL JNI_NAME(nValueText)(JNIEnv *env, jclass cls, jbyteArray s) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, s, &buf, &len)) return 0;
    corvid_value *v = corvid_value_text((const char *)buf, len);
    free((void *)(uintptr_t)buf);
    return (jlong)(intptr_t)v;
}

JNIEXPORT jlong JNICALL JNI_NAME(nValueBytes)(JNIEnv *env, jclass cls, jbyteArray b) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, b, &buf, &len)) return 0;
    corvid_value *v = corvid_value_bytes(buf, len);
    free((void *)(uintptr_t)buf);
    return (jlong)(intptr_t)v;
}

JNIEXPORT jlong JNICALL JNI_NAME(nValueVector)(JNIEnv *env, jclass cls, jfloatArray a) {
    (void)cls;
    if (a == NULL) return 0; /* the NULL-v rule (§4.3) */
    jsize n = (*env)->GetArrayLength(env, a);
    if (n > 0) {
        jfloat *p = (*env)->GetFloatArrayElements(env, a, NULL);
        if (p == NULL) return 0;
        corvid_value *v = corvid_value_vector((const float *)p, (size_t)n);
        (*env)->ReleaseFloatArrayElements(env, a, p, JNI_ABORT);
        return (jlong)(intptr_t)v;
    }
    return (jlong)(intptr_t)corvid_value_vector((const float *)&g_empty_byte, 0);
}

JNIEXPORT jlong JNICALL JNI_NAME(nValueArrayNew)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)corvid_value_array_new();
}

JNIEXPORT jint JNICALL JNI_NAME(nValueArrayPush)(JNIEnv *env, jclass cls, jlong arr,
                                                 jlong item) {
    (void)env; (void)cls;
    return (jint)corvid_value_array_push((corvid_value *)(intptr_t)arr,
                                         (corvid_value *)(intptr_t)item);
}

JNIEXPORT jlong JNICALL JNI_NAME(nValueMapNew)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)corvid_value_map_new();
}

JNIEXPORT jint JNICALL JNI_NAME(nValueMapPut)(JNIEnv *env, jclass cls, jlong map,
                                              jbyteArray key, jlong val) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, key, &buf, &len)) return (jint)CORVID_ERR;
    corvid_status st = corvid_value_map_put((corvid_value *)(intptr_t)map,
                                            (const char *)buf, len,
                                            (corvid_value *)(intptr_t)val);
    free((void *)(uintptr_t)buf);
    return (jint)st;
}

/* ---- value reads (§4.4) ---- */

JNIEXPORT jint JNICALL JNI_NAME(nValueType)(JNIEnv *env, jclass cls, jlong v) {
    (void)env; (void)cls;
    return (jint)corvid_value_type((const corvid_value *)(intptr_t)v);
}

JNIEXPORT jlong JNICALL JNI_NAME(nValueLen)(JNIEnv *env, jclass cls, jlong v) {
    (void)env; (void)cls;
    return (jlong)corvid_value_len((const corvid_value *)(intptr_t)v);
}

JNIEXPORT jboolean JNICALL JNI_NAME(nValueAsBool)(JNIEnv *env, jclass cls, jlong v,
                                                  jintArray ok) {
    (void)cls;
    int cok = 0;
    int r = corvid_value_as_bool((const corvid_value *)(intptr_t)v, &cok);
    put_int(env, ok, cok);
    return r ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL JNI_NAME(nValueAsInt)(JNIEnv *env, jclass cls, jlong v,
                                              jintArray ok) {
    (void)cls;
    int cok = 0;
    int64_t r = corvid_value_as_int((const corvid_value *)(intptr_t)v, &cok);
    put_int(env, ok, cok);
    return (jlong)r;
}

JNIEXPORT jdouble JNICALL JNI_NAME(nValueAsFloat)(JNIEnv *env, jclass cls, jlong v,
                                                  jintArray ok) {
    (void)cls;
    int cok = 0;
    double r = corvid_value_as_float((const corvid_value *)(intptr_t)v, &cok);
    put_int(env, ok, cok);
    return (jdouble)r;
}

JNIEXPORT jbyteArray JNICALL JNI_NAME(nValueTextRef)(JNIEnv *env, jclass cls,
                                                     jlong v) {
    (void)cls;
    if (corvid_value_type((const corvid_value *)(intptr_t)v) != CORVID_TYPE_TEXT)
        return NULL; /* wrong type: not an error, NULL per §4.4 */
    size_t len = 0;
    const char *p = corvid_value_text_ref((const corvid_value *)(intptr_t)v, &len);
    return bytes_to_jbytes(env, p ? p : (const char *)&g_empty_byte, len);
}

JNIEXPORT jbyteArray JNICALL JNI_NAME(nValueBytesRef)(JNIEnv *env, jclass cls,
                                                      jlong v) {
    (void)cls;
    if (corvid_value_type((const corvid_value *)(intptr_t)v) != CORVID_TYPE_BYTES)
        return NULL;
    size_t len = 0;
    const uint8_t *p = corvid_value_bytes_ref((const corvid_value *)(intptr_t)v, &len);
    return bytes_to_jbytes(env, p ? p : &g_empty_byte, len);
}

JNIEXPORT jfloatArray JNICALL JNI_NAME(nValueVectorRef)(JNIEnv *env, jclass cls,
                                                        jlong v) {
    (void)cls;
    if (corvid_value_type((const corvid_value *)(intptr_t)v) != CORVID_TYPE_VECTOR)
        return NULL;
    size_t dim = 0;
    const float *p = corvid_value_vector_ref((const corvid_value *)(intptr_t)v, &dim);
    jfloatArray a = (*env)->NewFloatArray(env, (jsize)dim);
    if (a == NULL) return NULL;
    if (dim > 0 && p != NULL)
        (*env)->SetFloatArrayRegion(env, a, 0, (jsize)dim, p);
    return a;
}

JNIEXPORT jlong JNICALL JNI_NAME(nValueArrayGet)(JNIEnv *env, jclass cls, jlong arr,
                                                 jlong index) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)corvid_value_array_get(
        (const corvid_value *)(intptr_t)arr, (size_t)index);
}

JNIEXPORT jlong JNICALL JNI_NAME(nValueMapGet)(JNIEnv *env, jclass cls, jlong map,
                                               jbyteArray key) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, key, &buf, &len)) return 0;
    const corvid_value *child = corvid_value_map_get(
        (const corvid_value *)(intptr_t)map, (const char *)buf, len);
    free((void *)(uintptr_t)buf);
    return (jlong)(intptr_t)child;
}

JNIEXPORT jlong JNICALL JNI_NAME(nValueMapKeys)(JNIEnv *env, jclass cls, jlong v) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)corvid_value_map_keys(
        (const corvid_value *)(intptr_t)v);
}

JNIEXPORT jlong JNICALL JNI_NAME(nValueClone)(JNIEnv *env, jclass cls, jlong v) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)corvid_value_clone((const corvid_value *)(intptr_t)v);
}

JNIEXPORT void JNICALL JNI_NAME(nValueFree)(JNIEnv *env, jclass cls, jlong v) {
    (void)env; (void)cls;
    corvid_value_free((corvid_value *)(intptr_t)v);
}

/* ---- strs cursor (§4.12) ---- */

JNIEXPORT jbyteArray JNICALL JNI_NAME(nStrsNext)(JNIEnv *env, jclass cls, jlong s) {
    (void)cls;
    const char *item = NULL;
    size_t len = 0;
    if (corvid_strs_next((corvid_strs *)(intptr_t)s, &item, &len) != 1)
        return NULL;
    return bytes_to_jbytes(env, item ? item : (const char *)&g_empty_byte, len);
}

JNIEXPORT void JNICALL JNI_NAME(nStrsFree)(JNIEnv *env, jclass cls, jlong s) {
    (void)env; (void)cls;
    corvid_strs_free((corvid_strs *)(intptr_t)s);
}

/* ---- db (§4.1) ---- */

JNIEXPORT jlong JNICALL JNI_NAME(nOpen)(JNIEnv *env, jclass cls, jbyteArray path) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, path, &buf, &len)) return 0;
    corvid_db *db = corvid_open((const char *)buf, len);
    free((void *)(uintptr_t)buf);
    return (jlong)(intptr_t)db;
}

JNIEXPORT jlong JNICALL JNI_NAME(nOpenMemory)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)corvid_open_memory();
}

JNIEXPORT jint JNICALL JNI_NAME(nClose)(JNIEnv *env, jclass cls, jlong db) {
    (void)env; (void)cls;
    return (jint)corvid_close((corvid_db *)(intptr_t)db);
}

JNIEXPORT jlong JNICALL JNI_NAME(nCollections)(JNIEnv *env, jclass cls, jlong db) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)corvid_collections((corvid_db *)(intptr_t)db);
}

/* ---- collection handle (§4.2) ---- */

JNIEXPORT jlong JNICALL JNI_NAME(nCollection)(JNIEnv *env, jclass cls, jlong db,
                                              jbyteArray name) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, name, &buf, &len)) return 0;
    corvid_coll *c = corvid_collection((corvid_db *)(intptr_t)db,
                                       (const char *)buf, len);
    free((void *)(uintptr_t)buf);
    return (jlong)(intptr_t)c;
}

JNIEXPORT jbyteArray JNICALL JNI_NAME(nCollectionName)(JNIEnv *env, jclass cls,
                                                       jlong c) {
    (void)cls;
    size_t len = 0;
    const char *name = corvid_collection_name((corvid_coll *)(intptr_t)c, &len);
    if (name == NULL) return NULL;
    return bytes_to_jbytes(env, name, len);
}

JNIEXPORT void JNICALL JNI_NAME(nCollectionFree)(JNIEnv *env, jclass cls, jlong c) {
    (void)env; (void)cls;
    corvid_collection_free((corvid_coll *)(intptr_t)c);
}

/* ---- mutations (§4.8) ---- */

JNIEXPORT jint JNICALL JNI_NAME(nInsert)(JNIEnv *env, jclass cls, jlong c,
                                         jbyteArray key, jobject doc) {
    (void)cls;
    corvid_value *dv = encode_value(env, doc);
    if (dv == NULL) return (jint)CORVID_ERR; /* exception pending */
    const uint8_t *kbuf = NULL;
    size_t klen = 0;
    if (!jbytes_to_copy(env, key, &kbuf, &klen)) {
        corvid_value_free(dv);
        return (jint)CORVID_ERR;
    }
    corvid_status st = corvid_insert((corvid_coll *)(intptr_t)c, kbuf, klen, dv);
    free((void *)(uintptr_t)kbuf);
    corvid_value_free(dv); /* the engine cloned its copy */
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nPutMany)(JNIEnv *env, jclass cls, jlong c,
                                          jobjectArray keys, jlongArray vals) {
    (void)cls;
    jsize count = (*env)->GetArrayLength(env, keys);
    if (count != (*env)->GetArrayLength(env, vals)) {
        (*env)->ThrowNew(env,
            (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
            "corvid: putMany keys/values length mismatch");
        return (jint)CORVID_ERR;
    }
    corvid_kv *items = (corvid_kv *)xmalloc_null(env,
        (size_t)count * sizeof(corvid_kv));
    if (items == NULL) return (jint)CORVID_ERR;
    jlong *vh = (*env)->GetLongArrayElements(env, vals, NULL);
    if (vh == NULL) { free(items); return (jint)CORVID_ERR; }
    jsize done = 0;
    for (; done < count; done++) {
        jbyteArray k = (jbyteArray)(*env)->GetObjectArrayElement(env, keys, done);
        if ((*env)->ExceptionCheck(env)) break;
        const uint8_t *buf = NULL;
        size_t len = 0;
        int ok = jbytes_to_copy(env, k, &buf, &len);
        (*env)->DeleteLocalRef(env, k);
        if (!ok) break;
        items[done].key = buf;
        items[done].key_len = len;
        items[done].val = (const corvid_value *)(intptr_t)vh[done];
    }
    corvid_status st = (done == count)
        ? corvid_put_many((corvid_coll *)(intptr_t)c, items, (size_t)count)
        : CORVID_ERR;
    for (jsize i = 0; i < done; i++)
        free((void *)(uintptr_t)items[i].key);
    free(items);
    (*env)->ReleaseLongArrayElements(env, vals, vh, JNI_ABORT);
    return (jint)st;
}

JNIEXPORT jbyteArray JNICALL JNI_NAME(nInsertAuto)(JNIEnv *env, jclass cls, jlong c,
                                                   jobject doc) {
    (void)cls;
    corvid_value *dv = encode_value(env, doc);
    if (dv == NULL) return NULL; /* exception pending */
    size_t klen = 0;
    uint8_t *key = corvid_insert_auto((corvid_coll *)(intptr_t)c, dv, &klen);
    corvid_value_free(dv);
    if (key == NULL) return NULL; /* failure; last error recorded */
    jbyteArray out = bytes_to_jbytes(env, key, klen);
    corvid_free(key); /* the ABI buffer deallocator's domain (§4.1) */
    return out;
}

JNIEXPORT jint JNICALL JNI_NAME(nUpdate)(JNIEnv *env, jclass cls, jlong c,
                                         jbyteArray key, jobject fn) {
    (void)cls;
    jmethodID mid = cb_invoke(env, fn, "(Ljava/lang/Object;)Ljava/lang/Object;");
    if (mid == NULL) return (jint)CORVID_ERR; /* exception pending */
    const uint8_t *kbuf = NULL;
    size_t klen = 0;
    if (!jbytes_to_copy(env, key, &kbuf, &klen)) return (jint)CORVID_ERR;
    cb_ctx ctx;
    ctx.env = env;
    ctx.fn = fn;
    ctx.mid = mid;
    corvid_status st = corvid_update((corvid_coll *)(intptr_t)c, kbuf, klen,
                                     update_bridge, &ctx);
    free((void *)(uintptr_t)kbuf);
    /* A pending user exception from the callback returns with us and
     * the JVM rethrows it at the Kotlin call site (docs/PLAN.md rule 6);
     * the engine's abort is additionally recorded in the thread-local
     * slot (CORVID_E_ARGUMENT) for callers who read it. */
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nPatch)(JNIEnv *env, jclass cls, jlong c,
                                        jbyteArray key, jobject patch) {
    (void)cls;
    corvid_value *pv = encode_value(env, patch);
    if (pv == NULL) return (jint)CORVID_ERR;
    const uint8_t *kbuf = NULL;
    size_t klen = 0;
    if (!jbytes_to_copy(env, key, &kbuf, &klen)) {
        corvid_value_free(pv);
        return (jint)CORVID_ERR;
    }
    corvid_status st = corvid_patch((corvid_coll *)(intptr_t)c, kbuf, klen, pv);
    free((void *)(uintptr_t)kbuf);
    corvid_value_free(pv);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nCompareAndSet)(JNIEnv *env, jclass cls, jlong c,
                                                jbyteArray key, jobject expected,
                                                jobject replacement,
                                                jintArray applied) {
    (void)cls;
    corvid_value *ex = NULL, *re = NULL;
    if (expected != NULL) {
        ex = encode_value(env, expected);
        if (ex == NULL) return (jint)CORVID_ERR;
    }
    if (replacement != NULL) {
        re = encode_value(env, replacement);
        if (re == NULL) {
            if (ex) corvid_value_free(ex);
            return (jint)CORVID_ERR;
        }
    }
    const uint8_t *kbuf = NULL;
    size_t klen = 0;
    if (!jbytes_to_copy(env, key, &kbuf, &klen)) {
        if (ex) corvid_value_free(ex);
        if (re) corvid_value_free(re);
        return (jint)CORVID_ERR;
    }
    int32_t ap = 0;
    corvid_status st = corvid_compare_and_set((corvid_coll *)(intptr_t)c, kbuf,
                                              klen, ex, re, &ap);
    free((void *)(uintptr_t)kbuf);
    if (ex) corvid_value_free(ex);
    if (re) corvid_value_free(re);
    put_int(env, applied, ap);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nDelete)(JNIEnv *env, jclass cls, jlong c,
                                         jbyteArray key, jintArray existed) {
    (void)cls;
    const uint8_t *kbuf = NULL;
    size_t klen = 0;
    if (!jbytes_to_copy(env, key, &kbuf, &klen)) return (jint)CORVID_ERR;
    int32_t ex = 0;
    corvid_status st = corvid_delete((corvid_coll *)(intptr_t)c, kbuf, klen, &ex);
    free((void *)(uintptr_t)kbuf);
    put_int(env, existed, ex);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nDeleteWhere)(JNIEnv *env, jclass cls, jlong c,
                                              jlong pred, jlongArray removed) {
    (void)env; (void)cls;
    size_t n = 0;
    corvid_status st = corvid_delete_where((corvid_coll *)(intptr_t)c,
                                           (corvid_pred *)(intptr_t)pred, &n);
    put_long(env, removed, (jlong)n);
    return (jint)st; /* pred consumed whatever the status (§8) */
}

JNIEXPORT jint JNICALL JNI_NAME(nDeleteBatch)(JNIEnv *env, jclass cls, jlong c,
                                              jobjectArray keys,
                                              jlongArray removed) {
    (void)cls;
    jsize count = (*env)->GetArrayLength(env, keys);
    const uint8_t **kbufs = (const uint8_t **)xmalloc_null(
        env, (size_t)count * sizeof(const uint8_t *));
    size_t *klens = (size_t *)xmalloc_null(env, (size_t)count * sizeof(size_t));
    if (kbufs == NULL || klens == NULL) {
        free(kbufs);
        free(klens);
        return (jint)CORVID_ERR;
    }
    jsize done = 0;
    for (; done < count; done++) {
        jbyteArray k = (jbyteArray)(*env)->GetObjectArrayElement(env, keys, done);
        if ((*env)->ExceptionCheck(env)) break;
        int ok = jbytes_to_copy(env, k, &kbufs[done], &klens[done]);
        (*env)->DeleteLocalRef(env, k);
        if (!ok) break;
    }
    corvid_status st;
    if (done == count) {
        size_t n = 0;
        st = corvid_delete_batch((corvid_coll *)(intptr_t)c,
                                 count > 0 ? kbufs : NULL,
                                 count > 0 ? klens : NULL, (size_t)count, &n);
        put_long(env, removed, (jlong)n);
    } else {
        st = CORVID_ERR;
    }
    for (jsize i = 0; i < done; i++) free((void *)(uintptr_t)kbufs[i]);
    free(kbufs);
    free(klens);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nInsertTTL)(JNIEnv *env, jclass cls, jlong c,
                                            jbyteArray key, jobject doc,
                                            jlong expiresAt) {
    (void)cls;
    corvid_value *dv = encode_value(env, doc);
    if (dv == NULL) return (jint)CORVID_ERR;
    const uint8_t *kbuf = NULL;
    size_t klen = 0;
    if (!jbytes_to_copy(env, key, &kbuf, &klen)) {
        corvid_value_free(dv);
        return (jint)CORVID_ERR;
    }
    corvid_status st = corvid_insert_with_ttl((corvid_coll *)(intptr_t)c, kbuf,
                                              klen, dv, (int64_t)expiresAt);
    free((void *)(uintptr_t)kbuf);
    corvid_value_free(dv);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nSetTTL)(JNIEnv *env, jclass cls, jlong c,
                                         jbyteArray key, jlong expiresAt) {
    (void)cls;
    const uint8_t *kbuf = NULL;
    size_t klen = 0;
    if (!jbytes_to_copy(env, key, &kbuf, &klen)) return (jint)CORVID_ERR;
    corvid_status st = corvid_set_ttl((corvid_coll *)(intptr_t)c, kbuf, klen,
                                      (int64_t)expiresAt);
    free((void *)(uintptr_t)kbuf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nGetTTL)(JNIEnv *env, jclass cls, jlong c,
                                         jbyteArray key, jlongArray expiresAtOut,
                                         jintArray hasTtl) {
    (void)cls;
    const uint8_t *kbuf = NULL;
    size_t klen = 0;
    if (!jbytes_to_copy(env, key, &kbuf, &klen)) return (jint)CORVID_ERR;
    int64_t at = 0;
    int32_t has = 0;
    corvid_status st = corvid_get_ttl((corvid_coll *)(intptr_t)c, kbuf, klen,
                                      &at, &has);
    free((void *)(uintptr_t)kbuf);
    put_long(env, expiresAtOut, (jlong)at);
    put_int(env, hasTtl, has);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nPurgeExpired)(JNIEnv *env, jclass cls, jlong c,
                                               jlong now, jlongArray purged) {
    (void)cls;
    size_t n = 0;
    corvid_status st = corvid_purge_expired((corvid_coll *)(intptr_t)c,
                                            (int64_t)now, &n);
    put_long(env, purged, (jlong)n);
    return (jint)st;
}

/* ---- reads (§4.9) ---- */

JNIEXPORT jlong JNICALL JNI_NAME(nGet)(JNIEnv *env, jclass cls, jlong c,
                                       jbyteArray key, jintArray status) {
    (void)cls;
    const uint8_t *kbuf = NULL;
    size_t klen = 0;
    if (!jbytes_to_copy(env, key, &kbuf, &klen)) {
        put_int(env, status, (jint)CORVID_ERR);
        return 0;
    }
    corvid_value *out = NULL;
    corvid_status st = corvid_get((corvid_coll *)(intptr_t)c, kbuf, klen, &out);
    free((void *)(uintptr_t)kbuf);
    put_int(env, status, (jint)st);
    return (jlong)(intptr_t)out; /* 0 = absent (ok) or failure (status) */
}

JNIEXPORT jint JNICALL JNI_NAME(nScan)(JNIEnv *env, jclass cls, jlong c,
                                       jobject sink) {
    (void)cls;
    jmethodID mid = cb_invoke(env, sink, "([BLjava/lang/Object;)Z");
    if (mid == NULL) return (jint)CORVID_ERR; /* exception pending */
    cb_ctx ctx;
    ctx.env = env;
    ctx.fn = sink;
    ctx.mid = mid;
    corvid_status st = corvid_scan((corvid_coll *)(intptr_t)c, scan_bridge, &ctx);
    /* Pending exception (a throwing sink) returns with us and surfaces
     * at the call site; the status below is then moot (rule 6). */
    return (jint)st;
}

JNIEXPORT jobjectArray JNICALL JNI_NAME(nPage)(JNIEnv *env, jclass cls, jlong c,
                                               jbyteArray after, jlong limit) {
    (void)cls;
    const uint8_t *abuf = NULL;
    size_t alen = 0;
    if (after != NULL && !jbytes_to_copy(env, after, &abuf, &alen)) return NULL;
    corvid_rows *rows = NULL;
    uint8_t *next = NULL;
    size_t next_len = 0;
    corvid_status st = corvid_page((corvid_coll *)(intptr_t)c,
                                   after != NULL ? abuf : NULL,
                                   after != NULL ? alen : 0,
                                   (size_t)limit, &rows, &next, &next_len);
    if (after != NULL) free((void *)(uintptr_t)abuf);
    if (st != CORVID_OK) return NULL; /* failure; last error recorded */
    /* [rowsHandle: Long, next: ByteArray?] */
    jobjectArray out = (*env)->NewObjectArray(env, 2, g_object_class, NULL);
    if (out == NULL) {
        corvid_rows_free(rows);
        corvid_free(next);
        return NULL;
    }
    jclass longCls = (*env)->FindClass(env, "java/lang/Long");
    if (longCls == NULL) {
        corvid_rows_free(rows);
        corvid_free(next);
        return NULL;
    }
    jobject boxed = (*env)->CallStaticObjectMethod(env, longCls, g_long_valueof,
                                                   (jlong)(intptr_t)rows);
    (*env)->DeleteLocalRef(env, longCls);
    if (boxed == NULL) {
        corvid_rows_free(rows);
        corvid_free(next);
        return NULL;
    }
    (*env)->SetObjectArrayElement(env, out, 0, boxed);
    (*env)->DeleteLocalRef(env, boxed);
    if (next != NULL) {
        jbyteArray jn = bytes_to_jbytes(env, next, next_len);
        corvid_free(next);
        if (jn == NULL) { corvid_rows_free(rows); return NULL; }
        (*env)->SetObjectArrayElement(env, out, 1, jn);
        (*env)->DeleteLocalRef(env, jn);
    } else {
        (*env)->SetObjectArrayElement(env, out, 1, NULL);
    }
    if ((*env)->ExceptionCheck(env)) { corvid_rows_free(rows); return NULL; }
    return out;
}

JNIEXPORT jint JNICALL JNI_NAME(nLen)(JNIEnv *env, jclass cls, jlong c,
                                      jlongArray out) {
    (void)cls;
    size_t n = 0;
    corvid_status st = corvid_len((corvid_coll *)(intptr_t)c, &n);
    put_long(env, out, (jlong)n);
    return (jint)st;
}

/* ---- predicates (§4.5) ---- */

JNIEXPORT jlong JNICALL JNI_NAME(nPredExists)(JNIEnv *env, jclass cls,
                                              jbyteArray path) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, path, &buf, &len)) return 0;
    corvid_pred *p = corvid_pred_exists((const char *)buf, len);
    free((void *)(uintptr_t)buf);
    return (jlong)(intptr_t)p;
}

JNIEXPORT jlong JNICALL JNI_NAME(nPredCompare)(JNIEnv *env, jclass cls,
                                               jbyteArray path, jint op,
                                               jobject value) {
    (void)cls;
    corvid_value *vv = encode_value(env, value);
    if (vv == NULL) return 0;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, path, &buf, &len)) {
        corvid_value_free(vv);
        return 0;
    }
    corvid_pred *p = corvid_pred_compare((const char *)buf, len,
                                         (corvid_cmp)op, vv);
    free((void *)(uintptr_t)buf);
    corvid_value_free(vv); /* CLONED into the tree (§5 rule 3) */
    return (jlong)(intptr_t)p;
}

JNIEXPORT jlong JNICALL JNI_NAME(nPredIn)(JNIEnv *env, jclass cls, jbyteArray path,
                                          jobjectArray values) {
    (void)cls;
    jsize count = (*env)->GetArrayLength(env, values);
    const corvid_value **vals = (const corvid_value **)xmalloc_null(
        env, (size_t)count * sizeof(corvid_value *));
    if (vals == NULL) return 0;
    jsize done = 0;
    for (; done < count; done++) {
        jobject v = (*env)->GetObjectArrayElement(env, values, done);
        if ((*env)->ExceptionCheck(env)) break;
        corvid_value *cv = encode_value(env, v);
        (*env)->DeleteLocalRef(env, v);
        if (cv == NULL) break;
        vals[done] = cv;
    }
    const uint8_t *buf = NULL;
    size_t len = 0;
    int havePath = jbytes_to_copy(env, path, &buf, &len);
    corvid_pred *p = NULL;
    if (havePath && done == count)
        p = corvid_pred_in((const char *)buf, len,
                           count > 0 ? vals : NULL, (size_t)count);
    for (jsize i = 0; i < done; i++)
        corvid_value_free((corvid_value *)(intptr_t)vals[i]);
    free(vals);
    if (havePath) free((void *)(uintptr_t)buf);
    return (jlong)(intptr_t)p;
}

JNIEXPORT jlong JNICALL JNI_NAME(nPredBetween)(JNIEnv *env, jclass cls,
                                               jbyteArray path, jobject low,
                                               jobject high) {
    (void)cls;
    corvid_value *lo = encode_value(env, low);
    if (lo == NULL) return 0;
    corvid_value *hi = encode_value(env, high);
    if (hi == NULL) { corvid_value_free(lo); return 0; }
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, path, &buf, &len)) {
        corvid_value_free(lo);
        corvid_value_free(hi);
        return 0;
    }
    corvid_pred *p = corvid_pred_between((const char *)buf, len, lo, hi);
    free((void *)(uintptr_t)buf);
    corvid_value_free(lo);
    corvid_value_free(hi);
    return (jlong)(intptr_t)p;
}

JNIEXPORT jlong JNICALL JNI_NAME(nPredStartsWith)(JNIEnv *env, jclass cls,
                                                  jbyteArray path,
                                                  jbyteArray prefix) {
    (void)cls;
    const uint8_t *pbuf = NULL, *fbuf = NULL;
    size_t plen = 0, flen = 0;
    if (!jbytes_to_copy(env, path, &pbuf, &plen)) return 0;
    if (!jbytes_to_copy(env, prefix, &fbuf, &flen)) {
        free((void *)(uintptr_t)pbuf);
        return 0;
    }
    corvid_pred *p = corvid_pred_starts_with((const char *)pbuf, plen,
                                             (const char *)fbuf, flen);
    free((void *)(uintptr_t)pbuf);
    free((void *)(uintptr_t)fbuf);
    return (jlong)(intptr_t)p;
}

JNIEXPORT jlong JNICALL JNI_NAME(nPredContains)(JNIEnv *env, jclass cls,
                                                jbyteArray path,
                                                jbyteArray substr) {
    (void)cls;
    const uint8_t *pbuf = NULL, *sbuf = NULL;
    size_t plen = 0, slen = 0;
    if (!jbytes_to_copy(env, path, &pbuf, &plen)) return 0;
    if (!jbytes_to_copy(env, substr, &sbuf, &slen)) {
        free((void *)(uintptr_t)pbuf);
        return 0;
    }
    corvid_pred *p = corvid_pred_contains((const char *)pbuf, plen,
                                          (const char *)sbuf, slen);
    free((void *)(uintptr_t)pbuf);
    free((void *)(uintptr_t)sbuf);
    return (jlong)(intptr_t)p;
}

JNIEXPORT jlong JNICALL JNI_NAME(nPredGeoWithin)(JNIEnv *env, jclass cls,
                                                 jbyteArray path, jdouble lat,
                                                 jdouble lon, jdouble radiusKm) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, path, &buf, &len)) return 0;
    corvid_pred *p = corvid_pred_geo_within((const char *)buf, len,
                                            (double)lat, (double)lon,
                                            (double)radiusKm);
    free((void *)(uintptr_t)buf);
    return (jlong)(intptr_t)p;
}

JNIEXPORT jlong JNICALL JNI_NAME(nPredAnd)(JNIEnv *env, jclass cls, jlong a,
                                           jlong b) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)corvid_pred_and((corvid_pred *)(intptr_t)a,
                                            (corvid_pred *)(intptr_t)b);
}

JNIEXPORT jlong JNICALL JNI_NAME(nPredOr)(JNIEnv *env, jclass cls, jlong a, jlong b) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)corvid_pred_or((corvid_pred *)(intptr_t)a,
                                           (corvid_pred *)(intptr_t)b);
}

JNIEXPORT jlong JNICALL JNI_NAME(nPredNot)(JNIEnv *env, jclass cls, jlong a) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)corvid_pred_not((corvid_pred *)(intptr_t)a);
}

JNIEXPORT void JNICALL JNI_NAME(nPredFree)(JNIEnv *env, jclass cls, jlong p) {
    (void)env; (void)cls;
    corvid_pred_free((corvid_pred *)(intptr_t)p);
}

/* ---- query builder (§4.6) ---- */

JNIEXPORT jlong JNICALL JNI_NAME(nQueryNew)(JNIEnv *env, jclass cls, jlong coll) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)corvid_query_new((corvid_coll *)(intptr_t)coll);
}

JNIEXPORT jint JNICALL JNI_NAME(nQueryFilter)(JNIEnv *env, jclass cls, jlong q,
                                              jlong pred) {
    (void)env; (void)cls;
    return (jint)corvid_query_filter((corvid_query *)(intptr_t)q,
                                     (corvid_pred *)(intptr_t)pred);
}

JNIEXPORT jint JNICALL JNI_NAME(nQueryVector)(JNIEnv *env, jclass cls, jlong q,
                                              jbyteArray field, jfloatArray query,
                                              jlong k, jint metric) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return (jint)CORVID_ERR;
    jsize dim = query != NULL ? (*env)->GetArrayLength(env, query) : 0;
    const float *elems = (const float *)&g_empty_byte;
    jfloat *pinned = NULL;
    if (query != NULL && dim > 0) {
        pinned = (*env)->GetFloatArrayElements(env, query, NULL);
        if (pinned == NULL) {
            free((void *)(uintptr_t)buf);
            return (jint)CORVID_ERR;
        }
        elems = (const float *)pinned;
    }
    corvid_status st = corvid_query_vector((corvid_query *)(intptr_t)q,
                                           (const char *)buf, len, elems,
                                           (size_t)dim, (size_t)k,
                                           (corvid_metric)metric);
    if (pinned != NULL)
        (*env)->ReleaseFloatArrayElements(env, query, pinned, JNI_ABORT);
    free((void *)(uintptr_t)buf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nQueryText)(JNIEnv *env, jclass cls, jlong q,
                                            jbyteArray field, jbyteArray s,
                                            jlong k) {
    (void)cls;
    const uint8_t *fbuf = NULL, *sbuf = NULL;
    size_t flen = 0, slen = 0;
    if (!jbytes_to_copy(env, field, &fbuf, &flen)) return (jint)CORVID_ERR;
    if (!jbytes_to_copy(env, s, &sbuf, &slen)) {
        free((void *)(uintptr_t)fbuf);
        return (jint)CORVID_ERR;
    }
    corvid_status st = corvid_query_text((corvid_query *)(intptr_t)q,
                                         (const char *)fbuf, flen,
                                         (const char *)sbuf, slen, (size_t)k);
    free((void *)(uintptr_t)fbuf);
    free((void *)(uintptr_t)sbuf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nQueryFuseRRF)(JNIEnv *env, jclass cls, jlong q,
                                               jfloat k) {
    (void)env; (void)cls;
    return (jint)corvid_query_fuse_rrf((corvid_query *)(intptr_t)q, (float)k);
}

JNIEXPORT jint JNICALL JNI_NAME(nQueryRerankMMR)(JNIEnv *env, jclass cls, jlong q,
                                                 jfloat lambda) {
    (void)env; (void)cls;
    return (jint)corvid_query_rerank_mmr((corvid_query *)(intptr_t)q, (float)lambda);
}

JNIEXPORT jint JNICALL JNI_NAME(nQueryApprox)(JNIEnv *env, jclass cls, jlong q) {
    (void)env; (void)cls;
    return (jint)corvid_query_approx((corvid_query *)(intptr_t)q);
}

JNIEXPORT jint JNICALL JNI_NAME(nQueryLimit)(JNIEnv *env, jclass cls, jlong q,
                                             jlong n) {
    (void)env; (void)cls;
    return (jint)corvid_query_limit((corvid_query *)(intptr_t)q, (size_t)n);
}

JNIEXPORT jint JNICALL JNI_NAME(nQueryOffset)(JNIEnv *env, jclass cls, jlong q,
                                              jlong n) {
    (void)env; (void)cls;
    return (jint)corvid_query_offset((corvid_query *)(intptr_t)q, (size_t)n);
}

JNIEXPORT jint JNICALL JNI_NAME(nQueryOrderBy)(JNIEnv *env, jclass cls, jlong q,
                                               jbyteArray field,
                                               jboolean descending) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return (jint)CORVID_ERR;
    corvid_status st = corvid_query_order_by((corvid_query *)(intptr_t)q,
                                             (const char *)buf, len,
                                             descending ? 1 : 0);
    free((void *)(uintptr_t)buf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nQuerySelect)(JNIEnv *env, jclass cls, jlong q,
                                              jobjectArray fields) {
    (void)cls;
    jsize count = (*env)->GetArrayLength(env, fields);
    const uint8_t **bufs = (const uint8_t **)xmalloc_null(
        env, (size_t)count * sizeof(const uint8_t *));
    size_t *lens = (size_t *)xmalloc_null(env, (size_t)count * sizeof(size_t));
    if (bufs == NULL || lens == NULL) {
        free(bufs);
        free(lens);
        return (jint)CORVID_ERR;
    }
    jsize done = 0;
    for (; done < count; done++) {
        jbyteArray f = (jbyteArray)(*env)->GetObjectArrayElement(env, fields, done);
        if ((*env)->ExceptionCheck(env)) break;
        int ok = jbytes_to_copy(env, f, &bufs[done], &lens[done]);
        (*env)->DeleteLocalRef(env, f);
        if (!ok) break;
    }
    corvid_status st = (done == count)
        ? corvid_query_select((corvid_query *)(intptr_t)q,
                              count > 0 ? (const char *const *)bufs : NULL,
                              count > 0 ? lens : NULL, (size_t)count)
        : CORVID_ERR;
    for (jsize i = 0; i < done; i++) free((void *)(uintptr_t)bufs[i]);
    free(bufs);
    free(lens);
    return (jint)st;
}

JNIEXPORT jlong JNICALL JNI_NAME(nQueryRun)(JNIEnv *env, jclass cls, jlong q) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)corvid_query_run((corvid_query *)(intptr_t)q);
}

JNIEXPORT void JNICALL JNI_NAME(nQueryFree)(JNIEnv *env, jclass cls, jlong q) {
    (void)env; (void)cls;
    corvid_query_free((corvid_query *)(intptr_t)q);
}

JNIEXPORT jlong JNICALL JNI_NAME(nPhraseSearch)(JNIEnv *env, jclass cls, jlong c,
                                                jbyteArray field, jbyteArray phrase,
                                                jlong k) {
    (void)cls;
    const uint8_t *fbuf = NULL, *pbuf = NULL;
    size_t flen = 0, plen = 0;
    if (!jbytes_to_copy(env, field, &fbuf, &flen)) return 0;
    if (!jbytes_to_copy(env, phrase, &pbuf, &plen)) {
        free((void *)(uintptr_t)fbuf);
        return 0;
    }
    corvid_rows *rows = corvid_phrase_search((corvid_coll *)(intptr_t)c,
                                             (const char *)fbuf, flen,
                                             (const char *)pbuf, plen,
                                             (size_t)k);
    free((void *)(uintptr_t)fbuf);
    free((void *)(uintptr_t)pbuf);
    return (jlong)(intptr_t)rows;
}

/* ---- rows cursor (§4.6) ---- */

/* One crossing per row: key bytes + fully decoded document + score. */
JNIEXPORT jobjectArray JNICALL JNI_NAME(nRowsNext)(JNIEnv *env, jclass cls,
                                                   jlong rows) {
    (void)cls;
    const uint8_t *key = NULL;
    size_t key_len = 0;
    const corvid_value *doc = NULL;
    float score = 0.0f;
    if (corvid_rows_next((corvid_rows *)(intptr_t)rows, &key, &key_len, &doc,
                         &score) != 1)
        return NULL;
    jobjectArray out = (*env)->NewObjectArray(env, 3, g_object_class, NULL);
    if (out == NULL) return NULL;
    jbyteArray jkey = bytes_to_jbytes(env,
                                      key ? key : &g_empty_byte, key_len);
    if (jkey == NULL) return NULL;
    (*env)->SetObjectArrayElement(env, out, 0, jkey);
    (*env)->DeleteLocalRef(env, jkey);
    jobject jdoc = decode_value(env, doc); /* inside the borrow window */
    (*env)->SetObjectArrayElement(env, out, 1, jdoc);
    if (jdoc != NULL) (*env)->DeleteLocalRef(env, jdoc);
    jclass floatCls = (*env)->FindClass(env, "java/lang/Float");
    if (floatCls == NULL) return NULL;
    jobject jsc = (*env)->CallStaticObjectMethod(env, floatCls, g_float_valueof,
                                                 (jfloat)score);
    (*env)->DeleteLocalRef(env, floatCls);
    if (jsc == NULL) return NULL;
    (*env)->SetObjectArrayElement(env, out, 2, jsc);
    (*env)->DeleteLocalRef(env, jsc);
    if ((*env)->ExceptionCheck(env)) return NULL;
    return out;
}

JNIEXPORT void JNICALL JNI_NAME(nRowsFree)(JNIEnv *env, jclass cls, jlong rows) {
    (void)env; (void)cls;
    corvid_rows_free((corvid_rows *)(intptr_t)rows);
}

/* ---- aggregations (§4.7) ---- */

JNIEXPORT jint JNICALL JNI_NAME(nQueryCount)(JNIEnv *env, jclass cls, jlong q,
                                             jlongArray out) {
    (void)cls;
    size_t n = 0;
    corvid_status st = corvid_query_count((corvid_query *)(intptr_t)q, &n);
    put_long(env, out, (jlong)n);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nQueryCountDistinct)(JNIEnv *env, jclass cls,
                                                     jlong q, jbyteArray field,
                                                     jlongArray out) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return (jint)CORVID_ERR;
    size_t n = 0;
    corvid_status st = corvid_query_count_distinct((corvid_query *)(intptr_t)q,
                                                   (const char *)buf, len, &n);
    free((void *)(uintptr_t)buf);
    put_long(env, out, (jlong)n);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nQuerySum)(JNIEnv *env, jclass cls, jlong q,
                                           jbyteArray field, jdoubleArray out) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return (jint)CORVID_ERR;
    double sum = 0;
    corvid_status st = corvid_query_sum((corvid_query *)(intptr_t)q,
                                        (const char *)buf, len, &sum);
    free((void *)(uintptr_t)buf);
    put_double(env, out, sum);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nQueryAvg)(JNIEnv *env, jclass cls, jlong q,
                                           jbyteArray field, jdoubleArray out,
                                           jintArray hasValue) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return (jint)CORVID_ERR;
    double avg = 0;
    int has = 0;
    corvid_status st = corvid_query_avg((corvid_query *)(intptr_t)q,
                                        (const char *)buf, len, &avg, &has);
    free((void *)(uintptr_t)buf);
    put_double(env, out, avg);
    put_int(env, hasValue, has);
    return (jint)st;
}

JNIEXPORT jlong JNICALL JNI_NAME(nQueryMin)(JNIEnv *env, jclass cls, jlong q,
                                            jbyteArray field, jintArray status) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) {
        put_int(env, status, (jint)CORVID_ERR);
        return 0;
    }
    corvid_value *out = NULL;
    corvid_status st = corvid_query_min((corvid_query *)(intptr_t)q,
                                        (const char *)buf, len, &out);
    free((void *)(uintptr_t)buf);
    put_int(env, status, (jint)st);
    return (jlong)(intptr_t)out;
}

JNIEXPORT jlong JNICALL JNI_NAME(nQueryMax)(JNIEnv *env, jclass cls, jlong q,
                                            jbyteArray field, jintArray status) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) {
        put_int(env, status, (jint)CORVID_ERR);
        return 0;
    }
    corvid_value *out = NULL;
    corvid_status st = corvid_query_max((corvid_query *)(intptr_t)q,
                                        (const char *)buf, len, &out);
    free((void *)(uintptr_t)buf);
    put_int(env, status, (jint)st);
    return (jlong)(intptr_t)out;
}

JNIEXPORT jlong JNICALL JNI_NAME(nQueryGroupCount)(JNIEnv *env, jclass cls, jlong q,
                                                   jbyteArray field) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return 0;
    corvid_groupiter *it = corvid_query_group_count((corvid_query *)(intptr_t)q,
                                                    (const char *)buf, len);
    free((void *)(uintptr_t)buf);
    return (jlong)(intptr_t)it;
}

JNIEXPORT jlong JNICALL JNI_NAME(nQueryGroupSum)(JNIEnv *env, jclass cls, jlong q,
                                                 jbyteArray groupField,
                                                 jbyteArray valueField) {
    (void)cls;
    const uint8_t *gbuf = NULL, *vbuf = NULL;
    size_t glen = 0, vlen = 0;
    if (!jbytes_to_copy(env, groupField, &gbuf, &glen)) return 0;
    if (!jbytes_to_copy(env, valueField, &vbuf, &vlen)) {
        free((void *)(uintptr_t)gbuf);
        return 0;
    }
    corvid_groupiter *it = corvid_query_group_sum((corvid_query *)(intptr_t)q,
                                                  (const char *)gbuf, glen,
                                                  (const char *)vbuf, vlen);
    free((void *)(uintptr_t)gbuf);
    free((void *)(uintptr_t)vbuf);
    return (jlong)(intptr_t)it;
}

JNIEXPORT jlong JNICALL JNI_NAME(nQueryGroupAvg)(JNIEnv *env, jclass cls, jlong q,
                                                 jbyteArray groupField,
                                                 jbyteArray valueField) {
    (void)cls;
    const uint8_t *gbuf = NULL, *vbuf = NULL;
    size_t glen = 0, vlen = 0;
    if (!jbytes_to_copy(env, groupField, &gbuf, &glen)) return 0;
    if (!jbytes_to_copy(env, valueField, &vbuf, &vlen)) {
        free((void *)(uintptr_t)gbuf);
        return 0;
    }
    corvid_groupiter *it = corvid_query_group_avg((corvid_query *)(intptr_t)q,
                                                  (const char *)gbuf, glen,
                                                  (const char *)vbuf, vlen);
    free((void *)(uintptr_t)gbuf);
    free((void *)(uintptr_t)vbuf);
    return (jlong)(intptr_t)it;
}

/* One crossing per group: [String key, Double value]. */
JNIEXPORT jobjectArray JNICALL JNI_NAME(nGroupIterNext)(JNIEnv *env, jclass cls,
                                                        jlong it) {
    (void)cls;
    const char *key = NULL;
    size_t key_len = 0;
    double value = 0;
    if (corvid_groupiter_next((corvid_groupiter *)(intptr_t)it, &key, &key_len,
                              &value) != 1)
        return NULL;
    jobjectArray out = (*env)->NewObjectArray(env, 2, g_object_class, NULL);
    if (out == NULL) return NULL;
    jstring jk = utf8_to_jstring(env,
                                 key ? key : (const char *)&g_empty_byte, key_len);
    if (jk == NULL) return NULL;
    (*env)->SetObjectArrayElement(env, out, 0, jk);
    (*env)->DeleteLocalRef(env, jk);
    jclass d = (*env)->FindClass(env, "java/lang/Double");
    if (d == NULL) return NULL;
    jobject jv = (*env)->CallStaticObjectMethod(env, d, g_double_valueof,
                                                (jdouble)value);
    (*env)->DeleteLocalRef(env, d);
    if (jv == NULL) return NULL;
    (*env)->SetObjectArrayElement(env, out, 1, jv);
    (*env)->DeleteLocalRef(env, jv);
    if ((*env)->ExceptionCheck(env)) return NULL;
    return out;
}

JNIEXPORT void JNICALL JNI_NAME(nGroupIterFree)(JNIEnv *env, jclass cls, jlong it) {
    (void)env; (void)cls;
    corvid_groupiter_free((corvid_groupiter *)(intptr_t)it);
}

/* The §7 inert rule exercised with a NULL handle (golden: AGG_G*). */
JNIEXPORT jboolean JNICALL JNI_NAME(nGroupIterNilNextOK)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return corvid_groupiter_next(NULL, NULL, NULL, NULL) == 0 ? JNI_TRUE : JNI_FALSE;
}

/* ---- graph (§4.11) ---- */

/* Keys are arbitrary bytes; relations are UTF-8 — both cross as byte
 * arrays (the wire type for everything, ruling 5). */
JNIEXPORT jint JNICALL JNI_NAME(nLink)(JNIEnv *env, jclass cls, jlong c,
                                       jbyteArray from, jbyteArray relation,
                                       jbyteArray to) {
    (void)cls;
    const uint8_t *fbuf = NULL, *rbuf = NULL, *tbuf = NULL;
    size_t flen = 0, rlen = 0, tlen = 0;
    if (!jbytes_to_copy(env, from, &fbuf, &flen)) return (jint)CORVID_ERR;
    if (!jbytes_to_copy(env, relation, &rbuf, &rlen) ||
        !jbytes_to_copy(env, to, &tbuf, &tlen)) {
        free((void *)(uintptr_t)fbuf);
        free((void *)(uintptr_t)rbuf);
        return (jint)CORVID_ERR;
    }
    corvid_status st = corvid_link((corvid_coll *)(intptr_t)c, fbuf, flen,
                                   (const char *)rbuf, rlen, tbuf, tlen);
    free((void *)(uintptr_t)fbuf);
    free((void *)(uintptr_t)rbuf);
    free((void *)(uintptr_t)tbuf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nLinkWeighted)(JNIEnv *env, jclass cls, jlong c,
                                               jbyteArray from, jbyteArray relation,
                                               jbyteArray to, jdouble weight) {
    (void)cls;
    const uint8_t *fbuf = NULL, *rbuf = NULL, *tbuf = NULL;
    size_t flen = 0, rlen = 0, tlen = 0;
    if (!jbytes_to_copy(env, from, &fbuf, &flen)) return (jint)CORVID_ERR;
    if (!jbytes_to_copy(env, relation, &rbuf, &rlen) ||
        !jbytes_to_copy(env, to, &tbuf, &tlen)) {
        free((void *)(uintptr_t)fbuf);
        free((void *)(uintptr_t)rbuf);
        return (jint)CORVID_ERR;
    }
    corvid_status st = corvid_link_weighted((corvid_coll *)(intptr_t)c, fbuf,
                                            flen, (const char *)rbuf, rlen,
                                            tbuf, tlen, (double)weight);
    free((void *)(uintptr_t)fbuf);
    free((void *)(uintptr_t)rbuf);
    free((void *)(uintptr_t)tbuf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nUnlink)(JNIEnv *env, jclass cls, jlong c,
                                         jbyteArray from, jbyteArray relation,
                                         jbyteArray to, jintArray removed) {
    (void)cls;
    const uint8_t *fbuf = NULL, *rbuf = NULL, *tbuf = NULL;
    size_t flen = 0, rlen = 0, tlen = 0;
    if (!jbytes_to_copy(env, from, &fbuf, &flen)) return (jint)CORVID_ERR;
    if (!jbytes_to_copy(env, relation, &rbuf, &rlen) ||
        !jbytes_to_copy(env, to, &tbuf, &tlen)) {
        free((void *)(uintptr_t)fbuf);
        free((void *)(uintptr_t)rbuf);
        return (jint)CORVID_ERR;
    }
    int32_t rem = 0;
    corvid_status st = corvid_unlink((corvid_coll *)(intptr_t)c, fbuf, flen,
                                     (const char *)rbuf, rlen, tbuf, tlen, &rem);
    free((void *)(uintptr_t)fbuf);
    free((void *)(uintptr_t)rbuf);
    free((void *)(uintptr_t)tbuf);
    put_int(env, removed, rem);
    return (jint)st;
}

JNIEXPORT jlong JNICALL JNI_NAME(nNeighbors)(JNIEnv *env, jclass cls, jlong c,
                                             jbyteArray from,
                                             jbyteArray relation) {
    (void)cls;
    const uint8_t *fbuf = NULL, *rbuf = NULL;
    size_t flen = 0, rlen = 0;
    if (!jbytes_to_copy(env, from, &fbuf, &flen)) return 0;
    if (!jbytes_to_copy(env, relation, &rbuf, &rlen)) {
        free((void *)(uintptr_t)fbuf);
        return 0;
    }
    corvid_strs *s = corvid_neighbors((corvid_coll *)(intptr_t)c, fbuf, flen,
                                      (const char *)rbuf, rlen);
    free((void *)(uintptr_t)fbuf);
    free((void *)(uintptr_t)rbuf);
    return (jlong)(intptr_t)s;
}

JNIEXPORT jlong JNICALL JNI_NAME(nInNeighbors)(JNIEnv *env, jclass cls, jlong c,
                                               jbyteArray to,
                                               jbyteArray relation) {
    (void)cls;
    const uint8_t *tbuf = NULL, *rbuf = NULL;
    size_t tlen = 0, rlen = 0;
    if (!jbytes_to_copy(env, to, &tbuf, &tlen)) return 0;
    if (!jbytes_to_copy(env, relation, &rbuf, &rlen)) {
        free((void *)(uintptr_t)tbuf);
        return 0;
    }
    corvid_strs *s = corvid_in_neighbors((corvid_coll *)(intptr_t)c, tbuf, tlen,
                                         (const char *)rbuf, rlen);
    free((void *)(uintptr_t)tbuf);
    free((void *)(uintptr_t)rbuf);
    return (jlong)(intptr_t)s;
}

JNIEXPORT jlong JNICALL JNI_NAME(nNeighborsWeighted)(JNIEnv *env, jclass cls,
                                                     jlong c, jbyteArray from,
                                                     jbyteArray relation) {
    (void)cls;
    const uint8_t *fbuf = NULL, *rbuf = NULL;
    size_t flen = 0, rlen = 0;
    if (!jbytes_to_copy(env, from, &fbuf, &flen)) return 0;
    if (!jbytes_to_copy(env, relation, &rbuf, &rlen)) {
        free((void *)(uintptr_t)fbuf);
        return 0;
    }
    corvid_geohits *h = corvid_neighbors_weighted((corvid_coll *)(intptr_t)c,
                                                  fbuf, flen,
                                                  (const char *)rbuf, rlen);
    free((void *)(uintptr_t)fbuf);
    free((void *)(uintptr_t)rbuf);
    return (jlong)(intptr_t)h;
}

JNIEXPORT jlong JNICALL JNI_NAME(nTraverse)(JNIEnv *env, jclass cls, jlong c,
                                            jbyteArray start, jbyteArray relation,
                                            jlong hops) {
    (void)cls;
    const uint8_t *sbuf = NULL, *rbuf = NULL;
    size_t slen = 0, rlen = 0;
    if (!jbytes_to_copy(env, start, &sbuf, &slen)) return 0;
    if (!jbytes_to_copy(env, relation, &rbuf, &rlen)) {
        free((void *)(uintptr_t)sbuf);
        return 0;
    }
    corvid_strs *s = corvid_traverse((corvid_coll *)(intptr_t)c, sbuf, slen,
                                     (const char *)rbuf, rlen, (size_t)hops);
    free((void *)(uintptr_t)sbuf);
    free((void *)(uintptr_t)rbuf);
    return (jlong)(intptr_t)s;
}

/* ---- geo (§4.12) ---- */

JNIEXPORT jlong JNICALL JNI_NAME(nGeoWithinRadius)(JNIEnv *env, jclass cls, jlong c,
                                                   jbyteArray field, jdouble lat,
                                                   jdouble lon,
                                                   jdouble radiusKm) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return 0;
    corvid_geohits *h = corvid_geo_within_radius((corvid_coll *)(intptr_t)c,
                                                 (const char *)buf, len,
                                                 (double)lat, (double)lon,
                                                 (double)radiusKm);
    free((void *)(uintptr_t)buf);
    return (jlong)(intptr_t)h;
}

JNIEXPORT jlong JNICALL JNI_NAME(nGeoWithinBBox)(JNIEnv *env, jclass cls, jlong c,
                                                 jbyteArray field, jdouble minLat,
                                                 jdouble minLon, jdouble maxLat,
                                                 jdouble maxLon) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return 0;
    corvid_geohits *h = corvid_geo_within_bbox((corvid_coll *)(intptr_t)c,
                                               (const char *)buf, len,
                                               (double)minLat, (double)minLon,
                                               (double)maxLat, (double)maxLon);
    free((void *)(uintptr_t)buf);
    return (jlong)(intptr_t)h;
}

JNIEXPORT jlong JNICALL JNI_NAME(nGeoNearest)(JNIEnv *env, jclass cls, jlong c,
                                              jbyteArray field, jdouble lat,
                                              jdouble lon, jlong k) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return 0;
    corvid_geohits *h = corvid_geo_nearest((corvid_coll *)(intptr_t)c,
                                           (const char *)buf, len, (double)lat,
                                           (double)lon, (size_t)k);
    free((void *)(uintptr_t)buf);
    return (jlong)(intptr_t)h;
}

/* One crossing per hit: [ByteArray key, Double distance, Object doc?]
 * (doc is null for neighbors_weighted cursors — §4.12's shape). */
JNIEXPORT jobjectArray JNICALL JNI_NAME(nGeohitsNext)(JNIEnv *env, jclass cls,
                                                      jlong h) {
    (void)cls;
    corvid_geohit hit;
    const corvid_value *doc = NULL;
    hit.key = NULL;
    hit.key_len = 0;
    hit.distance_km = 0.0;
    if (corvid_geohits_next((corvid_geohits *)(intptr_t)h, &hit, &doc) != 1)
        return NULL;
    jobjectArray out = (*env)->NewObjectArray(env, 3, g_object_class, NULL);
    if (out == NULL) return NULL;
    jbyteArray jkey = bytes_to_jbytes(env,
                                      hit.key ? hit.key : &g_empty_byte,
                                      hit.key_len);
    if (jkey == NULL) return NULL;
    (*env)->SetObjectArrayElement(env, out, 0, jkey);
    (*env)->DeleteLocalRef(env, jkey);
    jclass d = (*env)->FindClass(env, "java/lang/Double");
    if (d == NULL) return NULL;
    jobject jd = (*env)->CallStaticObjectMethod(env, d, g_double_valueof,
                                                (jdouble)hit.distance_km);
    (*env)->DeleteLocalRef(env, d);
    if (jd == NULL) return NULL;
    (*env)->SetObjectArrayElement(env, out, 1, jd);
    (*env)->DeleteLocalRef(env, jd);
    jobject jdoc = decode_value(env, doc);
    (*env)->SetObjectArrayElement(env, out, 2, jdoc);
    if (jdoc != NULL) (*env)->DeleteLocalRef(env, jdoc);
    if ((*env)->ExceptionCheck(env)) return NULL;
    return out;
}

JNIEXPORT void JNICALL JNI_NAME(nGeohitsFree)(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    corvid_geohits_free((corvid_geohits *)(intptr_t)h);
}

/* ---- indexes (§4.10) ---- */

JNIEXPORT jint JNICALL JNI_NAME(nCreateScalarIndex)(JNIEnv *env, jclass cls, jlong c,
                                                    jbyteArray field) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return (jint)CORVID_ERR;
    corvid_status st = corvid_create_scalar_index((corvid_coll *)(intptr_t)c,
                                                  (const char *)buf, len);
    free((void *)(uintptr_t)buf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nCreateCompoundIndex)(JNIEnv *env, jclass cls,
                                                      jlong c, jobjectArray fields) {
    (void)cls;
    jsize count = (*env)->GetArrayLength(env, fields);
    const uint8_t **bufs = (const uint8_t **)xmalloc_null(
        env, (size_t)count * sizeof(const uint8_t *));
    size_t *lens = (size_t *)xmalloc_null(env, (size_t)count * sizeof(size_t));
    if (bufs == NULL || lens == NULL) {
        free(bufs);
        free(lens);
        return (jint)CORVID_ERR;
    }
    jsize done = 0;
    for (; done < count; done++) {
        jbyteArray f = (jbyteArray)(*env)->GetObjectArrayElement(env, fields, done);
        if ((*env)->ExceptionCheck(env)) break;
        int ok = jbytes_to_copy(env, f, &bufs[done], &lens[done]);
        (*env)->DeleteLocalRef(env, f);
        if (!ok) break;
    }
    corvid_status st = (done == count)
        ? corvid_create_compound_index((corvid_coll *)(intptr_t)c,
                                       count > 0 ? (const char *const *)bufs : NULL,
                                       count > 0 ? lens : NULL, (size_t)count)
        : CORVID_ERR;
    for (jsize i = 0; i < done; i++) free((void *)(uintptr_t)bufs[i]);
    free(bufs);
    free(lens);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nCreateTextIndex)(JNIEnv *env, jclass cls, jlong c,
                                                  jbyteArray field) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return (jint)CORVID_ERR;
    corvid_status st = corvid_create_text_index((corvid_coll *)(intptr_t)c,
                                                (const char *)buf, len);
    free((void *)(uintptr_t)buf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nCreateTextIndexOnDisk)(JNIEnv *env, jclass cls,
                                                        jlong c, jbyteArray field) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return (jint)CORVID_ERR;
    corvid_status st = corvid_create_text_index_ondisk((corvid_coll *)(intptr_t)c,
                                                       (const char *)buf, len);
    free((void *)(uintptr_t)buf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nCreateGeoIndex)(JNIEnv *env, jclass cls, jlong c,
                                                 jbyteArray field) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return (jint)CORVID_ERR;
    corvid_status st = corvid_create_geo_index((corvid_coll *)(intptr_t)c,
                                               (const char *)buf, len);
    free((void *)(uintptr_t)buf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nCreateVectorIndex)(JNIEnv *env, jclass cls, jlong c,
                                                    jbyteArray field, jint metric) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return (jint)CORVID_ERR;
    corvid_status st = corvid_create_vector_index((corvid_coll *)(intptr_t)c,
                                                  (const char *)buf, len,
                                                  (corvid_metric)metric);
    free((void *)(uintptr_t)buf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nCreateVectorIndexQuantized)(JNIEnv *env, jclass cls,
                                                             jlong c,
                                                             jbyteArray field,
                                                             jint metric,
                                                             jint quant) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return (jint)CORVID_ERR;
    corvid_status st = corvid_create_vector_index_quantized(
        (corvid_coll *)(intptr_t)c, (const char *)buf, len,
        (corvid_metric)metric, (corvid_quant)quant);
    free((void *)(uintptr_t)buf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nCreateVectorIndexOnDisk)(JNIEnv *env, jclass cls,
                                                          jlong c, jbyteArray field,
                                                          jint metric) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return (jint)CORVID_ERR;
    corvid_status st = corvid_create_vector_index_ondisk(
        (corvid_coll *)(intptr_t)c, (const char *)buf, len, (corvid_metric)metric);
    free((void *)(uintptr_t)buf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nCreateVectorIndexOnDiskQuantized)(JNIEnv *env,
                                                                   jclass cls,
                                                                   jlong c,
                                                                   jbyteArray field,
                                                                   jint metric,
                                                                   jint quant) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return (jint)CORVID_ERR;
    corvid_status st = corvid_create_vector_index_ondisk_quantized(
        (corvid_coll *)(intptr_t)c, (const char *)buf, len,
        (corvid_metric)metric, (corvid_quant)quant);
    free((void *)(uintptr_t)buf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nCreateVectorIndexPQ)(JNIEnv *env, jclass cls,
                                                      jlong c, jbyteArray field,
                                                      jint metric, jlong m,
                                                      jlong k) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return (jint)CORVID_ERR;
    corvid_status st = corvid_create_vector_index_pq(
        (corvid_coll *)(intptr_t)c, (const char *)buf, len, (corvid_metric)metric,
        (size_t)m, (size_t)k);
    free((void *)(uintptr_t)buf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nCreateVectorIndexOnDiskPQ)(JNIEnv *env, jclass cls,
                                                            jlong c,
                                                            jbyteArray field,
                                                            jint metric, jlong m,
                                                            jlong k) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, field, &buf, &len)) return (jint)CORVID_ERR;
    corvid_status st = corvid_create_vector_index_ondisk_pq(
        (corvid_coll *)(intptr_t)c, (const char *)buf, len, (corvid_metric)metric,
        (size_t)m, (size_t)k);
    free((void *)(uintptr_t)buf);
    return (jint)st;
}

/* ---- schema (§4.10) ---- */

JNIEXPORT jint JNICALL JNI_NAME(nSetSchema)(JNIEnv *env, jclass cls, jlong c,
                                            jobjectArray names, jintArray types,
                                            jbooleanArray required,
                                            jbooleanArray unique) {
    (void)cls;
    jsize count = (*env)->GetArrayLength(env, names);
    if (count != (*env)->GetArrayLength(env, types) ||
        count != (*env)->GetArrayLength(env, required) ||
        count != (*env)->GetArrayLength(env, unique)) {
        (*env)->ThrowNew(env,
            (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
            "corvid: setSchema parallel arrays length mismatch");
        return (jint)CORVID_ERR;
    }
    corvid_field_def *defs = (corvid_field_def *)xmalloc_null(
        env, (size_t)count * sizeof(corvid_field_def));
    if (defs == NULL) return (jint)CORVID_ERR;
    jint *typesBuf = (*env)->GetIntArrayElements(env, types, NULL);
    jboolean *reqBuf = (*env)->GetBooleanArrayElements(env, required, NULL);
    jboolean *uniBuf = (*env)->GetBooleanArrayElements(env, unique, NULL);
    if (typesBuf == NULL || reqBuf == NULL || uniBuf == NULL) {
        if (typesBuf) (*env)->ReleaseIntArrayElements(env, types, typesBuf, JNI_ABORT);
        if (reqBuf) (*env)->ReleaseBooleanArrayElements(env, required, reqBuf, JNI_ABORT);
        if (uniBuf) (*env)->ReleaseBooleanArrayElements(env, unique, uniBuf, JNI_ABORT);
        free(defs);
        return (jint)CORVID_ERR;
    }
    jsize done = 0;
    for (; done < count; done++) {
        jbyteArray n = (jbyteArray)(*env)->GetObjectArrayElement(env, names, done);
        if ((*env)->ExceptionCheck(env)) break;
        const uint8_t *buf = NULL;
        size_t len = 0;
        int ok = jbytes_to_copy(env, n, &buf, &len);
        (*env)->DeleteLocalRef(env, n);
        if (!ok) break;
        defs[done].name = (const char *)buf;
        defs[done].name_len = len;
        defs[done].type = (corvid_field_type)typesBuf[done];
        defs[done].required = reqBuf[done] ? 1 : 0;
        defs[done].unique = uniBuf[done] ? 1 : 0;
    }
    corvid_status st = (done == count)
        ? corvid_set_schema((corvid_coll *)(intptr_t)c,
                            count > 0 ? defs : NULL, (size_t)count)
        : CORVID_ERR;
    for (jsize i = 0; i < done; i++) free((void *)(uintptr_t)defs[i].name);
    free(defs);
    (*env)->ReleaseIntArrayElements(env, types, typesBuf, JNI_ABORT);
    (*env)->ReleaseBooleanArrayElements(env, required, reqBuf, JNI_ABORT);
    (*env)->ReleaseBooleanArrayElements(env, unique, uniBuf, JNI_ABORT);
    return (jint)st;
}

JNIEXPORT jlong JNICALL JNI_NAME(nSchema)(JNIEnv *env, jclass cls, jlong c) {
    (void)env; (void)cls;
    corvid_schemaiter *it = NULL;
    if (corvid_schema((corvid_coll *)(intptr_t)c, &it) != CORVID_OK) return 0;
    return (jlong)(intptr_t)it; /* 0 also encodes "no schema declared" */
}

/* One crossing per field: [String name, Integer type, Boolean required,
 * Boolean unique]. */
JNIEXPORT jobjectArray JNICALL JNI_NAME(nSchemaIterNext)(JNIEnv *env, jclass cls,
                                                         jlong it) {
    (void)cls;
    corvid_field_def f;
    f.name = NULL;
    f.name_len = 0;
    f.type = CORVID_FIELD_ANY;
    f.required = 0;
    f.unique = 0;
    if (corvid_schemaiter_next((corvid_schemaiter *)(intptr_t)it, &f) != 1)
        return NULL;
    jobjectArray out = (*env)->NewObjectArray(env, 4, g_object_class, NULL);
    if (out == NULL) return NULL;
    jstring jn = utf8_to_jstring(env,
                                 f.name ? f.name : (const char *)&g_empty_byte,
                                 f.name_len);
    if (jn == NULL) return NULL;
    (*env)->SetObjectArrayElement(env, out, 0, jn);
    (*env)->DeleteLocalRef(env, jn);
    jclass ic = (*env)->FindClass(env, "java/lang/Integer");
    if (ic == NULL) return NULL;
    jobject jt = (*env)->CallStaticObjectMethod(env, ic, g_integer_valueof,
                                                (jint)f.type);
    (*env)->DeleteLocalRef(env, ic);
    if (jt == NULL) return NULL;
    (*env)->SetObjectArrayElement(env, out, 1, jt);
    (*env)->DeleteLocalRef(env, jt);
    jclass bc = (*env)->FindClass(env, "java/lang/Boolean");
    if (bc == NULL) return NULL;
    jobject jr = (*env)->CallStaticObjectMethod(env, bc, g_boolean_valueof,
                                                (jboolean)(f.required ? JNI_TRUE : JNI_FALSE));
    (*env)->DeleteLocalRef(env, bc);
    if (jr == NULL) return NULL;
    (*env)->SetObjectArrayElement(env, out, 2, jr);
    (*env)->DeleteLocalRef(env, jr);
    jclass bc2 = (*env)->FindClass(env, "java/lang/Boolean");
    if (bc2 == NULL) return NULL;
    jobject ju = (*env)->CallStaticObjectMethod(env, bc2, g_boolean_valueof,
                                                (jboolean)(f.unique ? JNI_TRUE : JNI_FALSE));
    (*env)->DeleteLocalRef(env, bc2);
    if (ju == NULL) return NULL;
    (*env)->SetObjectArrayElement(env, out, 3, ju);
    (*env)->DeleteLocalRef(env, ju);
    if ((*env)->ExceptionCheck(env)) return NULL;
    return out;
}

JNIEXPORT void JNICALL JNI_NAME(nSchemaIterFree)(JNIEnv *env, jclass cls, jlong it) {
    (void)env; (void)cls;
    corvid_schemaiter_free((corvid_schemaiter *)(intptr_t)it);
}

/* ---- admin & persistence (§4.13) ---- */

JNIEXPORT jint JNICALL JNI_NAME(nDump)(JNIEnv *env, jclass cls, jlong db,
                                       jbyteArray path) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, path, &buf, &len)) return (jint)CORVID_ERR;
    corvid_status st = corvid_dump_to_path((corvid_db *)(intptr_t)db,
                                           (const char *)buf, len);
    free((void *)(uintptr_t)buf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nLoad)(JNIEnv *env, jclass cls, jlong db,
                                       jbyteArray path) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, path, &buf, &len)) return (jint)CORVID_ERR;
    corvid_status st = corvid_load_from_path((corvid_db *)(intptr_t)db,
                                             (const char *)buf, len);
    free((void *)(uintptr_t)buf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nLoadWithRenames)(JNIEnv *env, jclass cls, jlong db,
                                                  jbyteArray path,
                                                  jobjectArray olds,
                                                  jobjectArray news) {
    (void)cls;
    const uint8_t *pbuf = NULL;
    size_t plen = 0;
    if (!jbytes_to_copy(env, path, &pbuf, &plen)) return (jint)CORVID_ERR;
    jsize count = (*env)->GetArrayLength(env, olds);
    if (count != (*env)->GetArrayLength(env, news)) {
        free((void *)(uintptr_t)pbuf);
        (*env)->ThrowNew(env,
            (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
            "corvid: loadWithRenames olds/news length mismatch");
        return (jint)CORVID_ERR;
    }
    const uint8_t **obufs = (const uint8_t **)xmalloc_null(
        env, (size_t)count * sizeof(const uint8_t *));
    const uint8_t **nbufs = (const uint8_t **)xmalloc_null(
        env, (size_t)count * sizeof(const uint8_t *));
    size_t *olens = (size_t *)xmalloc_null(env, (size_t)count * sizeof(size_t));
    size_t *nlens = (size_t *)xmalloc_null(env, (size_t)count * sizeof(size_t));
    if (obufs == NULL || nbufs == NULL || olens == NULL || nlens == NULL) {
        free(obufs);
        free(nbufs);
        free(olens);
        free(nlens);
        free((void *)(uintptr_t)pbuf);
        return (jint)CORVID_ERR;
    }
    jsize done = 0;
    for (; done < count; done++) {
        jbyteArray o = (jbyteArray)(*env)->GetObjectArrayElement(env, olds, done);
        if ((*env)->ExceptionCheck(env)) break;
        int ok = jbytes_to_copy(env, o, &obufs[done], &olens[done]);
        (*env)->DeleteLocalRef(env, o);
        if (!ok) break;
        jbyteArray n = (jbyteArray)(*env)->GetObjectArrayElement(env, news, done);
        if ((*env)->ExceptionCheck(env)) { done++; break; } /* o freed below */
        ok = jbytes_to_copy(env, n, &nbufs[done], &nlens[done]);
        (*env)->DeleteLocalRef(env, n);
        if (!ok) { done++; break; }
    }
    corvid_status st = (done == count)
        ? corvid_load_from_path_with_renames(
              (corvid_db *)(intptr_t)db, (const char *)pbuf, plen,
              count > 0 ? (const char *const *)obufs : NULL,
              count > 0 ? (const char *const *)nbufs : NULL,
              count > 0 ? olens : NULL, count > 0 ? nlens : NULL, (size_t)count)
        : CORVID_ERR;
    for (jsize i = 0; i < done; i++) {
        free((void *)(uintptr_t)obufs[i]);
        free((void *)(uintptr_t)nbufs[i]);
    }
    free(obufs);
    free(nbufs);
    free(olens);
    free(nlens);
    free((void *)(uintptr_t)pbuf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nBackup)(JNIEnv *env, jclass cls, jlong db,
                                         jbyteArray path) {
    (void)cls;
    const uint8_t *buf = NULL;
    size_t len = 0;
    if (!jbytes_to_copy(env, path, &buf, &len)) return (jint)CORVID_ERR;
    corvid_status st = corvid_backup((corvid_db *)(intptr_t)db,
                                     (const char *)buf, len);
    free((void *)(uintptr_t)buf);
    return (jint)st;
}

JNIEXPORT jint JNICALL JNI_NAME(nCompact)(JNIEnv *env, jclass cls, jlong db,
                                          jintArray movedOut) {
    (void)cls;
    int moved = 0;
    corvid_status st = corvid_compact((corvid_db *)(intptr_t)db, &moved);
    put_int(env, movedOut, moved);
    return (jint)st;
}

/* ---- the §7 null-free no-ops, exercised on their golden line ---- */

JNIEXPORT void JNICALL JNI_NAME(nNullFrees)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    corvid_value_free(NULL);
    corvid_pred_free(NULL);
    corvid_query_free(NULL);
    corvid_rows_free(NULL);
    corvid_strs_free(NULL);
    corvid_geohits_free(NULL);
    corvid_groupiter_free(NULL);
    corvid_schemaiter_free(NULL);
    corvid_collection_free(NULL);
    corvid_free(NULL);
}
