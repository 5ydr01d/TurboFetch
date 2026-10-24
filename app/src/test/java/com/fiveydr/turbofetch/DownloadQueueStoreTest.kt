package com.fiveydr.turbofetch

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

class DownloadQueueStoreTest {
    @Test
    fun transactionPersistsChangesAndSnapshotsAreIndependent() {
        val preferences = memoryPreferences()
        DownloadQueueStore.transaction(preferences) { rows ->
            rows.put(JSONObject().put("id", 7))
        }

        val snapshot = DownloadQueueStore.snapshot(preferences)
        assertEquals(1, snapshot.length())
        snapshot.put(JSONObject().put("id", 8))

        assertEquals("Stored queue must not be mutable through a returned snapshot", 1,
            DownloadQueueStore.snapshot(preferences).length())
    }

    @Test
    fun failedTransactionDoesNotPersistPartialMutations() {
        val preferences = memoryPreferences()
        DownloadQueueStore.transaction(preferences) { rows ->
            rows.put(JSONObject().put("id", 1))
        }

        val failure = runCatching {
            DownloadQueueStore.transaction(preferences) { rows ->
                rows.put(JSONObject().put("id", 2))
                error("abort transaction")
            }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals("abort transaction", failure?.message)
        assertEquals(1, DownloadQueueStore.snapshot(preferences).length())
    }

    @Test
    fun concurrentTransactionsDoNotLoseQueueUpdates() {
        val preferences = memoryPreferences()
        val workerCount = 8
        val rowsPerWorker = 25
        val workers = (0 until workerCount).map { worker ->
            Thread {
                repeat(rowsPerWorker) { row ->
                    DownloadQueueStore.transaction(preferences) { queue ->
                        queue.put(JSONObject().put("id", "$worker-$row"))
                    }
                }
            }
        }

        workers.forEach(Thread::start)
        workers.forEach(Thread::join)

        assertEquals(workerCount * rowsPerWorker,
            DownloadQueueStore.snapshot(preferences).length())
    }

    private fun memoryPreferences(): SharedPreferences {
        val values = ConcurrentHashMap<String, String>()
        lateinit var editor: SharedPreferences.Editor
        val editorHandler = InvocationHandler { _, method, args ->
            when (method.name) {
                "putString" -> {
                    val key = args!![0] as String
                    val value = args[1] as String?
                    if (value == null) values.remove(key) else values[key] = value
                    editor
                }
                "remove" -> {
                    values.remove(args!![0] as String)
                    editor
                }
                "clear" -> {
                    values.clear()
                    editor
                }
                "apply" -> null
                "commit" -> true
                "toString" -> "In-memory SharedPreferences.Editor"
                else -> throw UnsupportedOperationException("Unexpected editor call: ${method.name}")
            }
        }
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
            editorHandler
        ) as SharedPreferences.Editor

        val preferencesHandler = InvocationHandler { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0] as String] ?: args[1] as String?
                "contains" -> values.containsKey(args!![0] as String)
                "getAll" -> HashMap(values)
                "edit" -> editor
                "toString" -> "In-memory SharedPreferences"
                else -> throw UnsupportedOperationException("Unexpected preferences call: ${method.name}")
            }
        }
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
            preferencesHandler
        ) as SharedPreferences
    }
}
