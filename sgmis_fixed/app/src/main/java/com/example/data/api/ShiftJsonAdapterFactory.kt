package com.example.data.api

import com.example.data.model.Shift
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

/**
 * Custom Moshi JsonAdapter.Factory for Shift.
 * Handles the "no shift scheduled" response:
 * {"detail": "No shift scheduled for today.", "shift": null}
 * by safely returning null instead of throwing JsonDataException due to missing required fields.
 */
class ShiftJsonAdapterFactory : JsonAdapter.Factory {
    override fun create(
        type: Type,
        annotations: Set<Annotation>,
        moshi: Moshi
    ): JsonAdapter<*>? {
        if (Types.getRawType(type) != Shift::class.java) {
            return null
        }
        val delegateAdapter = moshi.nextAdapter<Shift>(this, type, annotations)
        return ShiftAdapter(delegateAdapter)
    }

    private class ShiftAdapter(
        private val delegate: JsonAdapter<Shift>
    ) : JsonAdapter<Shift?>() {

        override fun fromJson(reader: JsonReader): Shift? {
            if (reader.peek() == JsonReader.Token.NULL) {
                return reader.nextNull()
            }
            if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                reader.skipValue()
                return null
            }

            // Inspect the object using peekJson() to check for an "id" field
            val peekReader = reader.peekJson()
            peekReader.beginObject()
            var hasId = false
            while (peekReader.hasNext()) {
                val name = peekReader.nextName()
                if (name == "id" && peekReader.peek() != JsonReader.Token.NULL) {
                    hasId = true
                    break
                } else {
                    peekReader.skipValue()
                }
            }
            peekReader.close()

            return if (hasId) {
                delegate.fromJson(reader)
            } else {
                // Consume and ignore the "no shift" payload: {"detail": "...", "shift": null}
                reader.skipValue()
                null
            }
        }

        override fun toJson(writer: JsonWriter, value: Shift?) {
            if (value == null) {
                writer.nullValue()
            } else {
                delegate.toJson(writer, value)
            }
        }
    }
}
