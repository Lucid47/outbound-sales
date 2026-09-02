package com.lucid47.soheeyagaja.domain.importing

import java.io.PushbackReader
import java.io.Reader

/** Reads RFC 4180-style CSV records without loading the whole file in memory. */
class CsvRecordReader(reader: Reader) {
    private val input = PushbackReader(reader, 1)

    fun readRecord(): List<String>? {
        val record = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var readAnything = false

        while (true) {
            val value = input.read()
            if (value == -1) {
                if (!readAnything && record.isEmpty() && field.isEmpty()) return null
                record += field.toString()
                return record
            }

            readAnything = true
            when (val character = value.toChar()) {
                '"' -> {
                    if (!inQuotes && field.isEmpty()) {
                        inQuotes = true
                    } else if (inQuotes) {
                        val next = input.read()
                        if (next == '"'.code) {
                            field.append('"')
                        } else {
                            inQuotes = false
                            if (next != -1) input.unread(next)
                        }
                    } else {
                        field.append(character)
                    }
                }

                ',' -> if (inQuotes) {
                    field.append(character)
                } else {
                    record += field.toString()
                    field.clear()
                }

                '\n' -> if (inQuotes) {
                    field.append(character)
                } else {
                    record += field.toString()
                    return record
                }

                '\r' -> if (inQuotes) {
                    field.append('\n')
                } else {
                    val next = input.read()
                    if (next != '\n'.code && next != -1) input.unread(next)
                    record += field.toString()
                    return record
                }

                else -> field.append(character)
            }
        }
    }
}
