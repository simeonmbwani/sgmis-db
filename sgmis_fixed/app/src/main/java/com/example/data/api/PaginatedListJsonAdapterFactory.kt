package com.example.data.api

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

/**
 * Universal Moshi JsonAdapter.Factory for List<T>.
 * Automatically supports:
 * 1. DRF PageNumberPagination objects: {"count": 2, "next": null, "previous": null, "results": [...]}
 * 2. Raw JSON arrays: [...]
 * 3. Empty or null values: returns emptyList()
 */
class PaginatedListJsonAdapterFactory : JsonAdapter.Factory {
    override fun create(
        type: Type,
        annotations: Set<Annotation>,
        moshi: Moshi
    ): JsonAdapter<*>? {
        val rawType = Types.getRawType(type)
        if (rawType != List::class.java && rawType != Collection::class.java) {
            return null
        }
        val elementType = Types.collectionElementType(type, List::class.java)
        val elementAdapter = moshi.adapter<Any>(elementType)
        return PaginatedListAdapter(elementAdapter)
    }

    private class PaginatedListAdapter<T>(
        private val elementAdapter: JsonAdapter<T>
    ) : JsonAdapter<List<T>>() {

        override fun fromJson(reader: JsonReader): List<T> {
            val list = mutableListOf<T>()
            when (reader.peek()) {
                JsonReader.Token.BEGIN_ARRAY -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        elementAdapter.fromJson(reader)?.let { list.add(it) }
                    }
                    reader.endArray()
                }
                JsonReader.Token.BEGIN_OBJECT -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if (name == "results" && reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                elementAdapter.fromJson(reader)?.let { list.add(it) }
                            }
                            reader.endArray()
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                JsonReader.Token.NULL -> {
                    reader.nextNull<Any>()
                }
                else -> {
                    reader.skipValue()
                }
            }
            return list
        }

        override fun toJson(writer: JsonWriter, value: List<T>?) {
            if (value == null) {
                writer.nullValue()
                return
            }
            writer.beginArray()
            for (element in value) {
                elementAdapter.toJson(writer, element)
            }
            writer.endArray()
        }
    }
}
