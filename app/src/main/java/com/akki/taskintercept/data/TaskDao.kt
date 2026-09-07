package com.akki.taskintercept.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: Task)

    /**
     * Active (not-done) tasks ranked for display: HIGH first, then MEDIUM,
     * then LOW, newest first within a priority. The CASE is required
     * because the column stores enum names, whose alphabetical order
     * (HIGH < LOW < MEDIUM) is not the ranking order.
     */
    @Query(
        """
        SELECT * FROM tasks WHERE isDone = 0
        ORDER BY CASE priority
            WHEN 'HIGH' THEN 0
            WHEN 'MEDIUM' THEN 1
            ELSE 2
        END, createdAt DESC
        LIMIT :limit
        """
    )
    fun getActiveTasksByPriority(limit: Int): Flow<List<Task>>

    /** One-shot variant for callers without a coroutine scope to collect in (e.g. the overlay). */
    @Query(
        """
        SELECT * FROM tasks WHERE isDone = 0
        ORDER BY CASE priority
            WHEN 'HIGH' THEN 0
            WHEN 'MEDIUM' THEN 1
            ELSE 2
        END, createdAt DESC
        LIMIT :limit
        """
    )
    suspend fun getActiveTasksByPriorityOnce(limit: Int): List<Task>

    /** Every saved task, most recent first — for the history view. */
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAllTasksDesc(): Flow<List<Task>>

    /** Marks a task done — it stays in history but stops being "active". */
    @Query("UPDATE tasks SET isDone = 1 WHERE id = :id")
    suspend fun markDone(id: Int)

    /** Edits a task in place (title + priority), keeping its id/createdAt/history row. */
    @Query("UPDATE tasks SET title = :title, priority = :priority WHERE id = :id")
    suspend fun updateTask(id: Int, title: String, priority: Priority)

    @Query("DELETE FROM tasks")
    suspend fun clear()
}
