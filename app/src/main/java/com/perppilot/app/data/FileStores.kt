package com.perppilot.app.data

import android.util.AtomicFile
import com.perppilot.core.carry.CarryState
import com.perppilot.core.carry.CarryStore
import com.perppilot.core.engine.EngineState
import com.perppilot.core.engine.EngineStore
import com.perppilot.core.events.EventState
import com.perppilot.core.events.EventStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** JSON file persistence with atomic writes, exposing the latest value as a StateFlow for the UI. */
open class JsonFileStore<T>(file: File, private val serializer: KSerializer<T>, private val default: () -> T) {
    private val atomic = AtomicFile(file)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val flow = MutableStateFlow(read())
    val state: StateFlow<T> get() = flow

    private fun read(): T = runCatching {
        json.decodeFromString(serializer, atomic.readFully().toString(Charsets.UTF_8))
    }.getOrElse { default() }

    @Synchronized
    fun get(): T = flow.value

    @Synchronized
    fun put(value: T) {
        val out = atomic.startWrite()
        try {
            out.write(json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
        flow.value = value
    }
}

class FileEngineStore(file: File) : JsonFileStore<EngineState>(file, EngineState.serializer(), { EngineState() }), EngineStore {
    override fun load(): EngineState = get()
    override fun save(state: EngineState) = put(state)
}

class FileEventStore(file: File) : JsonFileStore<EventState>(file, EventState.serializer(), { EventState() }), EventStore {
    override fun load(): EventState = get()
    override fun save(state: EventState) = put(state)
}

class FileCarryStore(file: File) : JsonFileStore<CarryState>(file, CarryState.serializer(), { CarryState() }), CarryStore {
    override fun load(): CarryState = get()
    override fun save(state: CarryState) = put(state)
}
