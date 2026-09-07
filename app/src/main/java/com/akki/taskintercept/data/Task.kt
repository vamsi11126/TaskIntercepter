package com.akki.taskintercept.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Task importance. Room stores this as the enum's name (a readable String
 * column) — no TypeConverter needed. SQL-side ordering can't use the
 * column's alphabetical order (HIGH < LOW < MEDIUM), so ranking queries
 * order via an explicit CASE expression instead.
 */
enum class Priority {
    HIGH, MEDIUM, LOW
}

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val createdAt: Long,
    /** True once the user marks the task done — a done task is never "active". */
    val isDone: Boolean = false,
    val priority: Priority = Priority.MEDIUM
)
