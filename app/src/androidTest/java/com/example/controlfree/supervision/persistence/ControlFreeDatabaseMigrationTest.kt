package com.example.controlfree.supervision.persistence

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlFreeDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ControlFreeDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @After
    fun tearDown() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun 从版本16迁移到版本17会创建多App触发关联并级联清理() {
        helper.createDatabase(DATABASE_NAME, 16).apply {
            insertPlan("trigger-plan")
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            17,
            true,
            ControlFreeDatabase.MIGRATION_16_17
        ).use { migrated ->
            assertTrue(hasTable(migrated, "supervision_plan_trigger_apps"))
            assertTrue(
                hasIndex(
                    migrated,
                    "supervision_plan_trigger_apps",
                    "index_supervision_plan_trigger_apps_plan_id"
                )
            )
            assertTrue(
                hasIndex(
                    migrated,
                    "supervision_plan_trigger_apps",
                    "index_supervision_plan_trigger_apps_package_name"
                )
            )
            assertEquals(0L, countRows(migrated, "supervision_plan_trigger_apps"))

            migrated.execSQL(
                "INSERT INTO supervision_plan_trigger_apps (plan_id, package_name) VALUES " +
                    "('trigger-plan', 'example.video'), " +
                    "('trigger-plan', 'example.music')"
            )
            assertEquals(2L, countRows(migrated, "supervision_plan_trigger_apps"))
            assertEquals(1L, foreignKeyCount(migrated, "supervision_plan_trigger_apps"))
            assertEquals(
                "CASCADE",
                foreignKeyDeleteAction(migrated, "supervision_plan_trigger_apps")
            )

            migrated.execSQL("PRAGMA foreign_keys = ON")
            migrated.execSQL("DELETE FROM supervision_plans WHERE plan_id = 'trigger-plan'")
            assertEquals(0L, countRows(migrated, "supervision_plan_trigger_apps"))
        }
    }

    @Test
    fun 从版本15迁移到版本16会保留旧行为并创建禁用时段表() {
        helper.createDatabase(DATABASE_NAME, 15).apply {
            execSQL(
                "INSERT INTO supervision_plans " +
                    "(plan_id, name, plan_type, enabled, zone_id, zone_mode, " +
                    "active_days_mask, created_at_epoch_millis, updated_at_epoch_millis, " +
                    "one_time_start_epoch_millis, one_time_end_epoch_millis) " +
                    "VALUES ('app-a', '视频监督', 'APP', 1, 'Asia/Shanghai', " +
                    "'FOLLOW_DEVICE', 127, 1, 2, NULL, NULL)"
            )
            execSQL(
                "INSERT INTO app_supervision_policies " +
                    "(plan_id, package_name, usage_allowance_minutes, rest_duration_minutes) " +
                    "VALUES ('app-a', 'example.video', 30, 10)"
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            16,
            true,
            ControlFreeDatabase.MIGRATION_15_16
        ).use { migrated ->
            assertEquals(
                1_440L,
                scalarLong(
                    migrated,
                    "SELECT daily_usage_limit_minutes FROM app_supervision_policies " +
                        "WHERE plan_id = 'app-a'"
                )
            )
            assertTrue(hasTable(migrated, "app_supervision_disabled_time_ranges"))
            assertEquals(0L, countRows(migrated, "app_supervision_disabled_time_ranges"))
        }
    }

    @Test
    fun 从版本1迁移到版本2会保留计划并创建空历史表() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                "INSERT INTO supervision_plans " +
                    "(plan_id, name, plan_type, enabled, zone_id, zone_mode, " +
                    "active_days_mask, created_at_epoch_millis, updated_at_epoch_millis) " +
                    "VALUES ('plan-a', '计划A', 'GLOBAL', 1, 'Asia/Shanghai', " +
                    "'FOLLOW_DEVICE', 127, 1, 1)"
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            ControlFreeDatabase.MIGRATION_1_2
        ).use { migrated ->
            assertEquals(1L, countRows(migrated, "supervision_plans"))
            assertEquals(0L, countRows(migrated, "supervision_sessions"))
        }
    }

    @Test
    fun 从版本2迁移到版本3会保留计划并创建空预约表() {
        helper.createDatabase(DATABASE_NAME, 2).apply {
            insertPlan("plan-b")
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            3,
            true,
            ControlFreeDatabase.MIGRATION_2_3
        ).use { migrated ->
            assertEquals(1L, countRows(migrated, "supervision_plans"))
            assertEquals(0L, countRows(migrated, "plan_activation_reservations"))

            migrated.execSQL(
                "INSERT INTO plan_activation_reservations " +
                    "(plan_id, enable_at_epoch_millis) VALUES ('plan-b', 1000)"
            )
            migrated.execSQL("DELETE FROM supervision_plans WHERE plan_id = 'plan-b'")
            assertEquals(0L, countRows(migrated, "plan_activation_reservations"))
        }
    }

    @Test
    fun 从版本1连续迁移到版本3会同时创建历史和预约表() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            insertPlan("plan-c")
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            3,
            true,
            ControlFreeDatabase.MIGRATION_1_2,
            ControlFreeDatabase.MIGRATION_2_3
        ).use { migrated ->
            assertEquals(1L, countRows(migrated, "supervision_plans"))
            assertEquals(0L, countRows(migrated, "supervision_sessions"))
            assertEquals(0L, countRows(migrated, "plan_activation_reservations"))
        }
    }

    @Test
    fun 从版本3迁移到版本4会创建成长账户流水周期和订单表() {
        helper.createDatabase(DATABASE_NAME, 3).apply {
            insertPlan("plan-growth")
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            4,
            true,
            ControlFreeDatabase.MIGRATION_3_4
        ).use { migrated ->
            assertEquals(1L, countRows(migrated, "supervision_plans"))
            assertEquals(1L, countRows(migrated, "growth_accounts"))
            assertEquals(1L, countRows(migrated, "growth_ledger"))
            assertEquals(0L, countRows(migrated, "growth_cycle_outcomes"))
            assertEquals(0L, countRows(migrated, "growth_unlock_orders"))
            assertEquals(
                60L,
                scalarLong(
                    migrated,
                    "SELECT balance_points FROM growth_accounts WHERE account_id = 'default'"
                )
            )
            assertEquals(
                0L,
                scalarLong(
                    migrated,
                    "SELECT lifetime_experience FROM growth_accounts WHERE account_id = 'default'"
                )
            )
        }
    }

    @Test
    fun 从版本1连续迁移到版本4会保留旧数据并完成全部新表() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            insertPlan("plan-all")
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            4,
            true,
            ControlFreeDatabase.MIGRATION_1_2,
            ControlFreeDatabase.MIGRATION_2_3,
            ControlFreeDatabase.MIGRATION_3_4
        ).use { migrated ->
            assertEquals(1L, countRows(migrated, "supervision_plans"))
            assertEquals(0L, countRows(migrated, "supervision_sessions"))
            assertEquals(0L, countRows(migrated, "plan_activation_reservations"))
            assertEquals(1L, countRows(migrated, "growth_accounts"))
            assertEquals(1L, countRows(migrated, "growth_ledger"))
        }
    }

    @Test
    fun 从版本4迁移到版本5会创建清单基础表且不提前引入版本6字段() {
        helper.createDatabase(DATABASE_NAME, 4).apply {
            insertPlan("plan-list-v5")
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            5,
            true,
            ControlFreeDatabase.MIGRATION_4_5
        ).use { migrated ->
            assertEquals(1L, countRows(migrated, "supervision_plans"))
            assertEquals(0L, countRows(migrated, "todo_items"))
            assertEquals(0L, countRows(migrated, "habit_items"))
            assertEquals(0L, countRows(migrated, "habit_records"))
            assertEquals(0L, countRows(migrated, "anniversary_items"))
            assertEquals(0L, countRows(migrated, "quick_notes"))

            assertTrue(hasColumn(migrated, "todo_items", "created_at_epoch_millis"))
            assertTrue(hasColumn(migrated, "habit_items", "frequency_type"))
            assertTrue(hasColumn(migrated, "anniversary_items", "show_on_widget"))
            assertTrue(hasColumn(migrated, "quick_notes", "media_uri"))
            assertFalse(hasColumn(migrated, "todo_items", "scheduled_start_epoch_millis"))
            assertFalse(hasColumn(migrated, "habit_items", "weekdays_mask"))
            assertFalse(hasColumn(migrated, "anniversary_items", "source_year"))
            assertFalse(hasColumn(migrated, "quick_notes", "capture_source"))
        }
    }

    @Test
    fun 从版本5迁移到版本6会保留清单数据回填新增字段并创建联动表() {
        helper.createDatabase(DATABASE_NAME, 5).apply {
            assertFalse(hasColumn(this, "todo_items", "scheduled_start_epoch_millis"))
            assertFalse(hasColumn(this, "habit_items", "weekdays_mask"))
            assertFalse(hasColumn(this, "anniversary_items", "source_year"))
            assertFalse(hasColumn(this, "quick_notes", "capture_source"))
            VERSION_6_TABLES.forEach { table ->
                assertFalse("版本5不应包含 $table", hasTable(this, table))
            }
            insertVersion5ProductivityData()
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            6,
            true,
            ControlFreeDatabase.MIGRATION_5_6
        ).use { migrated ->
            assertEquals("旧待办", scalarString(migrated, "SELECT title FROM todo_items WHERE id = 'todo-v5'"))
            assertEquals(1L, scalarLong(migrated, "SELECT is_important FROM todo_items WHERE id = 'todo-v5'"))
            assertEquals("URGENT", scalarString(migrated, "SELECT urgency_mode FROM todo_items WHERE id = 'todo-v5'"))
            assertEquals("DAILY", scalarString(migrated, "SELECT recurrence_type FROM todo_items WHERE id = 'todo-v5'"))
            assertEquals(
                1_704_067_200_000L,
                scalarLong(migrated, "SELECT updated_at_epoch_millis FROM todo_items WHERE id = 'todo-v5'")
            )

            assertEquals(
                "SPECIFIC_WEEKDAYS",
                scalarString(migrated, "SELECT frequency_type FROM habit_items WHERE id = 'habit-v5'")
            )
            assertEquals(
                "2024-01-01",
                scalarString(migrated, "SELECT start_date FROM habit_items WHERE id = 'habit-v5'")
            )
            assertEquals(127L, scalarLong(migrated, "SELECT weekdays_mask FROM habit_items WHERE id = 'habit-v5'"))
            assertEquals(
                1L,
                scalarLong(migrated, "SELECT completion_count FROM habit_records WHERE id = 'record-v5'")
            )
            assertEquals(
                1_704_153_600_000L,
                scalarLong(migrated, "SELECT updated_at_epoch_millis FROM habit_records WHERE id = 'record-v5'")
            )

            assertEquals(
                2024L,
                scalarLong(migrated, "SELECT source_year FROM anniversary_items WHERE id = 'anniversary-v5'")
            )
            assertEquals(
                1L,
                scalarLong(migrated, "SELECT source_month FROM anniversary_items WHERE id = 'anniversary-v5'")
            )
            assertEquals(
                1L,
                scalarLong(migrated, "SELECT source_day FROM anniversary_items WHERE id = 'anniversary-v5'")
            )
            assertEquals(
                8L,
                scalarLong(migrated, "SELECT source_hour FROM anniversary_items WHERE id = 'anniversary-v5'")
            )
            assertEquals(
                "Asia/Shanghai",
                scalarString(migrated, "SELECT zone_id FROM anniversary_items WHERE id = 'anniversary-v5'")
            )

            assertEquals("旧闪念", scalarString(migrated, "SELECT content FROM quick_notes WHERE id = 'note-v5'"))
            assertEquals("TEXT", scalarString(migrated, "SELECT capture_source FROM quick_notes WHERE id = 'note-v5'"))
            assertEquals(
                1_704_067_200_000L,
                scalarLong(migrated, "SELECT updated_at_epoch_millis FROM quick_notes WHERE id = 'note-v5'")
            )

            VERSION_6_TABLES.forEach { table ->
                assertEquals("新表 $table 应为空", 0L, countRows(migrated, table))
            }
            assertTrue(hasColumn(migrated, "todo_subtasks", "parent_subtask_id"))
            assertTrue(hasColumn(migrated, "commitment_occurrences", "satisfied_at_epoch_millis"))
            assertTrue(hasColumn(migrated, "productivity_reward_outbox", "processed_at_epoch_millis"))
        }
    }

    @Test
    fun 从版本1连续迁移到版本6会保留监督数据并创建完整清单结构() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            insertPlan("plan-through-v6")
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            6,
            true,
            ControlFreeDatabase.MIGRATION_1_2,
            ControlFreeDatabase.MIGRATION_2_3,
            ControlFreeDatabase.MIGRATION_3_4,
            ControlFreeDatabase.MIGRATION_4_5,
            ControlFreeDatabase.MIGRATION_5_6
        ).use { migrated ->
            assertEquals(1L, countRows(migrated, "supervision_plans"))
            assertEquals(
                "plan-through-v6",
                scalarString(migrated, "SELECT plan_id FROM supervision_plans LIMIT 1")
            )
            assertEquals(1L, countRows(migrated, "growth_accounts"))
            assertEquals(1L, countRows(migrated, "growth_ledger"))
            assertEquals(0L, countRows(migrated, "todo_items"))
            assertTrue(hasColumn(migrated, "todo_items", "scheduled_start_epoch_millis"))
            assertTrue(hasColumn(migrated, "habit_records", "completion_count"))
            assertTrue(hasColumn(migrated, "anniversary_items", "show_on_lock_screen"))
            assertTrue(hasColumn(migrated, "quick_notes", "media_mime_type"))
            VERSION_6_TABLES.forEach { table ->
                assertEquals("连续迁移应创建 $table", 0L, countRows(migrated, table))
            }
        }
    }

    @Test
    fun 从版本6迁移到版本7会保留旧计划并为空的一次性时间窗() {
        helper.createDatabase(DATABASE_NAME, 6).apply {
            insertPlan("plan-v7")
            assertFalse(hasColumn(this, "supervision_plans", "one_time_start_epoch_millis"))
            assertFalse(hasColumn(this, "supervision_plans", "one_time_end_epoch_millis"))
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            7,
            true,
            ControlFreeDatabase.MIGRATION_6_7
        ).use { migrated ->
            assertEquals(1L, countRows(migrated, "supervision_plans"))
            assertTrue(hasColumn(migrated, "supervision_plans", "one_time_start_epoch_millis"))
            assertTrue(hasColumn(migrated, "supervision_plans", "one_time_end_epoch_millis"))
            assertTrue(
                scalarIsNull(
                    migrated,
                    "SELECT one_time_start_epoch_millis FROM supervision_plans " +
                        "WHERE plan_id = 'plan-v7'"
                )
            )
            assertTrue(
                scalarIsNull(
                    migrated,
                    "SELECT one_time_end_epoch_millis FROM supervision_plans " +
                        "WHERE plan_id = 'plan-v7'"
                )
            )
        }
    }

    @Test
    fun 从版本1连续迁移到版本7会保留旧计划并完成一次性专注结构() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            insertPlan("plan-through-v7")
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            7,
            true,
            ControlFreeDatabase.MIGRATION_1_2,
            ControlFreeDatabase.MIGRATION_2_3,
            ControlFreeDatabase.MIGRATION_3_4,
            ControlFreeDatabase.MIGRATION_4_5,
            ControlFreeDatabase.MIGRATION_5_6,
            ControlFreeDatabase.MIGRATION_6_7
        ).use { migrated ->
            assertEquals(
                "plan-through-v7",
                scalarString(migrated, "SELECT plan_id FROM supervision_plans LIMIT 1")
            )
            assertTrue(hasColumn(migrated, "supervision_plans", "one_time_start_epoch_millis"))
            assertTrue(hasColumn(migrated, "supervision_plans", "one_time_end_epoch_millis"))
            assertTrue(hasColumn(migrated, "todo_items", "associated_focus_plan_id"))
        }
    }

    @Test
    fun 从版本7迁移到版本8会保留待办关联并创建关联索引() {
        helper.createDatabase(DATABASE_NAME, 7).apply {
            insertVersion5ProductivityData()
            assertTrue(hasColumn(this, "todo_items", "associated_focus_plan_id"))
            assertFalse(
                hasIndex(
                    this,
                    "todo_items",
                    "index_todo_items_associated_focus_plan_id"
                )
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            8,
            true,
            ControlFreeDatabase.MIGRATION_7_8
        ).use { migrated ->
            assertEquals(1L, countRows(migrated, "todo_items"))
            assertEquals(
                "旧待办",
                scalarString(migrated, "SELECT title FROM todo_items WHERE id = 'todo-v5'")
            )
            assertEquals(
                "focus-v5",
                scalarString(
                    migrated,
                    "SELECT associated_focus_plan_id FROM todo_items WHERE id = 'todo-v5'"
                )
            )
            assertTrue(
                hasIndex(
                    migrated,
                    "todo_items",
                    "index_todo_items_associated_focus_plan_id"
                )
            )
        }
    }

    @Test
    fun 从版本8迁移到版本9会保留旧数据并创建初版账本结构() {
        helper.createDatabase(DATABASE_NAME, 8).apply {
            insertVersion5ProductivityData()
            assertFalse(hasTable(this, "ledger_entries"))
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            9,
            true,
            ControlFreeDatabase.MIGRATION_8_9
        ).use { migrated ->
            assertEquals("旧待办", scalarString(migrated, "SELECT title FROM todo_items WHERE id = 'todo-v5'"))
            assertEquals("旧闪念", scalarString(migrated, "SELECT content FROM quick_notes WHERE id = 'note-v5'"))
            assertEquals(
                "content://legacy/image",
                scalarString(migrated, "SELECT media_uri FROM quick_notes WHERE id = 'note-v5'")
            )
            assertEquals(0L, countRows(migrated, "ledger_entries"))

            listOf(
                "id",
                "amount",
                "direction",
                "category",
                "title",
                "note",
                "occurred_at_epoch_millis",
                "source_note_id",
                "isEstimated",
                "emotion",
                "necessity",
                "createdAtEpochMillis",
                "updatedAtEpochMillis"
            ).forEach { column ->
                assertTrue("账本缺少字段 $column", hasColumn(migrated, "ledger_entries", column))
            }
            listOf(
                "index_ledger_entries_occurred_at_epoch_millis",
                "index_ledger_entries_direction",
                "index_ledger_entries_category",
                "index_ledger_entries_source_note_id"
            ).forEach { index ->
                assertTrue("账本缺少索引 $index", hasIndex(migrated, "ledger_entries", index))
            }

            migrated.execSQL(
                "INSERT INTO ledger_entries " +
                    "(id, amount, direction, category, title, occurred_at_epoch_millis, " +
                    "source_note_id, isEstimated, createdAtEpochMillis, updatedAtEpochMillis) VALUES " +
                    "('ledger-v9', 2800, 'EXPENSE', 'FOOD', '午餐', 1000, 'note-v5', 0, 2000, 2000)"
            )
            assertEquals(0L, scalarLong(migrated, "SELECT isEstimated FROM ledger_entries WHERE id = 'ledger-v9'"))
        }
    }

    @Test
    fun 从版本9迁移到版本10会保留账目并回填AI审计字段() {
        helper.createDatabase(DATABASE_NAME, 9).apply {
            execSQL(
                "INSERT INTO ledger_entries " +
                    "(id, amount, direction, category, title, occurred_at_epoch_millis, " +
                    "isEstimated, createdAtEpochMillis, updatedAtEpochMillis) VALUES " +
                    "('ledger-v9', 2800, 'EXPENSE', 'FOOD', '午餐', 1000, 0, 2000, 2000)"
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            10,
            true,
            ControlFreeDatabase.MIGRATION_9_10
        ).use { migrated ->
            assertEquals(1L, countRows(migrated, "ledger_entries"))
            assertEquals("午餐", scalarString(migrated, "SELECT title FROM ledger_entries WHERE id = 'ledger-v9'"))
            listOf(
                "ai_confidence",
                "source_type",
                "ai_warnings_json",
                "source_batch_id"
            ).forEach { column ->
                assertTrue("账本缺少字段 $column", hasColumn(migrated, "ledger_entries", column))
            }
            assertTrue(
                hasIndex(
                    migrated,
                    "ledger_entries",
                    "index_ledger_entries_source_batch_id"
                )
            )
            assertEquals(0L, scalarLong(migrated, "SELECT CAST(ai_confidence AS INTEGER) FROM ledger_entries WHERE id = 'ledger-v9'"))
            assertEquals("AI", scalarString(migrated, "SELECT source_type FROM ledger_entries WHERE id = 'ledger-v9'"))
            assertTrue(scalarIsNull(migrated, "SELECT ai_warnings_json FROM ledger_entries WHERE id = 'ledger-v9'"))
            assertTrue(scalarIsNull(migrated, "SELECT source_batch_id FROM ledger_entries WHERE id = 'ledger-v9'"))
        }
    }

    @Test
    fun 从旧发布版版本9迁移到版本10会规范化isEstimated默认值并保留数据() {
        helper.createDatabase(DATABASE_NAME, 9).apply {
            // 早期 8->9 migration 实际创建过 DEFAULT 0，而导出的 v9 schema 没有默认值。
            // 手工重建出该历史结构，确保 9->10 对已发布数据库也能通过 Room schema 校验。
            recreateLegacyVersion9LedgerTable()
            execSQL(
                "INSERT INTO ledger_entries " +
                    "(id, amount, direction, category, title, note, occurred_at_epoch_millis, " +
                    "source_note_id, isEstimated, emotion, necessity, createdAtEpochMillis, " +
                    "updatedAtEpochMillis) VALUES " +
                    "('legacy-ledger-v9', 2800, 'EXPENSE', 'FOOD', '旧午餐', '旧备注', " +
                    "1000, 'legacy-note-v9', 1, '刚需', 'need', 2000, 2000)"
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            10,
            true,
            ControlFreeDatabase.MIGRATION_9_10
        ).use { migrated ->
            assertEquals(1L, countRows(migrated, "ledger_entries"))
            assertEquals(
                1L,
                scalarLong(migrated, "SELECT isEstimated FROM ledger_entries WHERE id = 'legacy-ledger-v9'")
            )
            assertFalse(hasColumnDefault(migrated, "ledger_entries", "isEstimated"))
            assertEquals(
                "legacy-note-v9",
                scalarString(migrated, "SELECT source_note_id FROM ledger_entries WHERE id = 'legacy-ledger-v9'")
            )
            assertEquals(
                "旧备注",
                scalarString(migrated, "SELECT note FROM ledger_entries WHERE id = 'legacy-ledger-v9'")
            )
            assertEquals(
                "AI",
                scalarString(migrated, "SELECT source_type FROM ledger_entries WHERE id = 'legacy-ledger-v9'")
            )
            assertEquals(
                "[\"由旧版本迁移，原始估算警告不可用\"]",
                scalarString(migrated, "SELECT ai_warnings_json FROM ledger_entries WHERE id = 'legacy-ledger-v9'")
            )
        }
    }

    @Test
    fun 旧版本9非法账目迁移后会完整保留旧字段并补齐新列() {
        val longTitle = "超长标题".repeat(6)
        val longNote = "旧备注".repeat(70)
        val longSourceNoteId = "n".repeat(201)
        helper.createDatabase(DATABASE_NAME, 9).apply {
            recreateLegacyVersion9LedgerTable()
            execSQL(
                "INSERT INTO ledger_entries " +
                    "(id, amount, direction, category, title, note, occurred_at_epoch_millis, " +
                    "source_note_id, isEstimated, emotion, necessity, createdAtEpochMillis, " +
                    "updatedAtEpochMillis) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "illegal-ledger-v9",
                    -2_800L,
                    "UNKNOWN",
                    "LEGACY_CATEGORY",
                    longTitle,
                    longNote,
                    -1L,
                    longSourceNoteId,
                    1,
                    "随意",
                    "maybe",
                    -2L,
                    -3L
                )
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            10,
            true,
            ControlFreeDatabase.MIGRATION_9_10
        ).use { migrated ->
            assertEquals(1L, countRows(migrated, "ledger_entries"))
            assertEquals(
                "illegal-ledger-v9",
                scalarString(migrated, "SELECT id FROM ledger_entries")
            )
            assertEquals(-2_800L, scalarLong(migrated, "SELECT amount FROM ledger_entries"))
            assertEquals("UNKNOWN", scalarString(migrated, "SELECT direction FROM ledger_entries"))
            assertEquals(
                "LEGACY_CATEGORY",
                scalarString(migrated, "SELECT category FROM ledger_entries")
            )
            assertEquals(longTitle, scalarString(migrated, "SELECT title FROM ledger_entries"))
            assertEquals(longNote, scalarString(migrated, "SELECT note FROM ledger_entries"))
            assertEquals(
                -1L,
                scalarLong(migrated, "SELECT occurred_at_epoch_millis FROM ledger_entries")
            )
            assertEquals(
                longSourceNoteId,
                scalarString(migrated, "SELECT source_note_id FROM ledger_entries")
            )
            assertEquals(1L, scalarLong(migrated, "SELECT isEstimated FROM ledger_entries"))
            assertEquals("随意", scalarString(migrated, "SELECT emotion FROM ledger_entries"))
            assertEquals("maybe", scalarString(migrated, "SELECT necessity FROM ledger_entries"))
            assertEquals(
                -2L,
                scalarLong(migrated, "SELECT createdAtEpochMillis FROM ledger_entries")
            )
            assertEquals(
                -3L,
                scalarLong(migrated, "SELECT updatedAtEpochMillis FROM ledger_entries")
            )
            assertEquals(0L, scalarLong(migrated, "SELECT ai_confidence FROM ledger_entries"))
            assertEquals("AI", scalarString(migrated, "SELECT source_type FROM ledger_entries"))
            assertEquals(
                "[\"由旧版本迁移，原始估算警告不可用\"]",
                scalarString(migrated, "SELECT ai_warnings_json FROM ledger_entries")
            )
            assertTrue(scalarIsNull(migrated, "SELECT source_batch_id FROM ledger_entries"))
        }
    }

    @Test
    fun 从版本1连续迁移到版本10会保留监督数据并建立账本() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            insertPlan("plan-through-v10")
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            10,
            true,
            ControlFreeDatabase.MIGRATION_1_2,
            ControlFreeDatabase.MIGRATION_2_3,
            ControlFreeDatabase.MIGRATION_3_4,
            ControlFreeDatabase.MIGRATION_4_5,
            ControlFreeDatabase.MIGRATION_5_6,
            ControlFreeDatabase.MIGRATION_6_7,
            ControlFreeDatabase.MIGRATION_7_8,
            ControlFreeDatabase.MIGRATION_8_9,
            ControlFreeDatabase.MIGRATION_9_10
        ).use { migrated ->
            assertEquals(
                "plan-through-v10",
                scalarString(migrated, "SELECT plan_id FROM supervision_plans LIMIT 1")
            )
            assertTrue(hasTable(migrated, "ledger_entries"))
            assertEquals(0L, countRows(migrated, "ledger_entries"))
        }
    }

    @Test
    fun 从版本11迁移到版本12会创建设备重启历史事件表() {
        helper.createDatabase(DATABASE_NAME, 11).close()

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            12,
            true,
            ControlFreeDatabase.MIGRATION_11_12
        ).use { migrated ->
            assertTrue(hasTable(migrated, "supervision_history_events"))
            assertEquals(0L, countRows(migrated, "supervision_history_events"))
            assertTrue(
                hasIndex(
                    migrated,
                    "supervision_history_events",
                    "index_supervision_history_events_boot_instance_key"
                )
            )
        }
    }

    @Test
    fun 从版本12迁移到版本13会保留闪记并增加AI助理字段() {
        helper.createDatabase(DATABASE_NAME, 12).use { database ->
            database.execSQL(
                "INSERT INTO quick_notes " +
                    "(id, content, media_uri, status, created_at_epoch_millis, capture_source, " +
                    "updated_at_epoch_millis) VALUES " +
                    "('note-v12', '旧闪记', NULL, 'RAW', 1000, 'TEXT', 1000)"
            )
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            13,
            true,
            ControlFreeDatabase.MIGRATION_12_13
        ).use { migrated ->
            assertTrue(hasColumn(migrated, "quick_notes", "ai_advice"))
            assertTrue(hasColumn(migrated, "quick_notes", "ai_assistant_fingerprint"))
            assertEquals(
                "旧闪记",
                scalarString(migrated, "SELECT content FROM quick_notes WHERE id = 'note-v12'")
            )
            assertTrue(
                scalarIsNull(migrated, "SELECT ai_advice FROM quick_notes WHERE id = 'note-v12'")
            )
            assertTrue(
                scalarIsNull(
                    migrated,
                    "SELECT ai_assistant_fingerprint FROM quick_notes WHERE id = 'note-v12'"
                )
            )
        }
    }

    @Test
    fun 从版本13迁移到版本14会创建独立日程事件表() {
        helper.createDatabase(DATABASE_NAME, 13).close()

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            14,
            true,
            ControlFreeDatabase.MIGRATION_13_14
        ).use { migrated ->
            assertTrue(hasTable(migrated, "time_block_events"))
            assertEquals(0L, countRows(migrated, "time_block_events"))
            assertTrue(
                hasIndex(
                    migrated,
                    "time_block_events",
                    "index_time_block_events_start_at_epoch_millis"
                )
            )
        }
    }

    @Test
    fun 从版本14迁移到版本15会将日程完整迁移为待办并保留同ID的两份数据() {
        helper.createDatabase(DATABASE_NAME, 14).use { database ->
            database.execSQL(
                """
                INSERT INTO `todo_items` (
                    `id`, `title`, `description`, `due_date_epoch_millis`, `priority`,
                    `is_completed`, `completed_at_epoch_millis`, `category`, `repeat_rule`,
                    `associated_focus_plan_id`, `supervision_lock_enabled`,
                    `created_at_epoch_millis`, `updated_at_epoch_millis`
                ) VALUES (
                    'shared-id', '原待办', '不得覆盖', 9000, 3,
                    0, NULL, '原分类', 'DAILY', NULL, 1, 100, 200
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO `time_block_events` (
                    `id`, `title`, `description`, `start_at_epoch_millis`,
                    `end_at_epoch_millis`, `project`, `priority`, `is_completed`,
                    `completed_at_epoch_millis`, `created_at_epoch_millis`,
                    `updated_at_epoch_millis`
                ) VALUES
                    (
                        'event-full', '完整日程', '日程说明', 1000000,
                        6400000, '项目甲', 2, 1, 6500000, 111, 222
                    ),
                    (
                        'event-short', '极短日程', NULL, 7000000,
                        7030000, '项目乙', 1, 0, NULL, 333, 444
                    ),
                    (
                        'event-long', '超长日程', NULL, 8000000,
                        22400000, '项目丙', 3, 0, NULL, 555, 666
                    ),
                    (
                        'shared-id', '冲突日程', '不应覆盖原待办', 23000000,
                        26600000, '冲突项目', 0, 0, NULL, 777, 888
                    )
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            15,
            true,
            ControlFreeDatabase.MIGRATION_14_15
        ).use { migrated ->
            assertEquals(0L, countRows(migrated, "time_block_events"))
            assertEquals(5L, countRows(migrated, "todo_items"))

            assertEquals(
                "完整日程",
                scalarString(migrated, "SELECT title FROM todo_items WHERE id = 'event-full'")
            )
            assertEquals(
                "日程说明",
                scalarString(
                    migrated,
                    "SELECT description FROM todo_items WHERE id = 'event-full'"
                )
            )
            assertTrue(
                scalarIsNull(
                    migrated,
                    "SELECT due_date_epoch_millis FROM todo_items WHERE id = 'event-full'"
                )
            )
            assertEquals(
                2L,
                scalarLong(migrated, "SELECT priority FROM todo_items WHERE id = 'event-full'")
            )
            assertEquals(
                1L,
                scalarLong(
                    migrated,
                    "SELECT is_completed FROM todo_items WHERE id = 'event-full'"
                )
            )
            assertEquals(
                6500000L,
                scalarLong(
                    migrated,
                    "SELECT completed_at_epoch_millis FROM todo_items WHERE id = 'event-full'"
                )
            )
            assertEquals(
                "项目甲",
                scalarString(migrated, "SELECT category FROM todo_items WHERE id = 'event-full'")
            )
            assertEquals(
                1000000L,
                scalarLong(
                    migrated,
                    "SELECT scheduled_start_epoch_millis FROM todo_items WHERE id = 'event-full'"
                )
            )
            assertEquals(
                6400000L,
                scalarLong(
                    migrated,
                    "SELECT scheduled_end_epoch_millis FROM todo_items WHERE id = 'event-full'"
                )
            )
            assertEquals(
                90L,
                scalarLong(
                    migrated,
                    "SELECT estimated_focus_minutes FROM todo_items WHERE id = 'event-full'"
                )
            )
            assertEquals(
                1L,
                scalarLong(migrated, "SELECT is_important FROM todo_items WHERE id = 'event-full'")
            )
            assertEquals(
                "AUTO",
                scalarString(migrated, "SELECT urgency_mode FROM todo_items WHERE id = 'event-full'")
            )
            assertEquals(
                "NONE",
                scalarString(
                    migrated,
                    "SELECT recurrence_type FROM todo_items WHERE id = 'event-full'"
                )
            )
            assertEquals(
                111L,
                scalarLong(
                    migrated,
                    "SELECT created_at_epoch_millis FROM todo_items WHERE id = 'event-full'"
                )
            )
            assertEquals(
                222L,
                scalarLong(
                    migrated,
                    "SELECT updated_at_epoch_millis FROM todo_items WHERE id = 'event-full'"
                )
            )
            assertEquals(
                0L,
                scalarLong(
                    migrated,
                    "SELECT supervision_lock_enabled FROM todo_items WHERE id = 'event-full'"
                )
            )
            assertTrue(
                scalarIsNull(migrated, "SELECT repeat_rule FROM todo_items WHERE id = 'event-full'")
            )
            assertTrue(
                scalarIsNull(
                    migrated,
                    "SELECT associated_focus_plan_id FROM todo_items WHERE id = 'event-full'"
                )
            )
            assertTrue(
                scalarIsNull(migrated, "SELECT reminders_json FROM todo_items WHERE id = 'event-full'")
            )
            assertEquals(
                1L,
                scalarLong(
                    migrated,
                    "SELECT estimated_focus_minutes FROM todo_items WHERE id = 'event-short'"
                )
            )
            assertEquals(
                0L,
                scalarLong(
                    migrated,
                    "SELECT is_important FROM todo_items WHERE id = 'event-short'"
                )
            )
            assertEquals(
                180L,
                scalarLong(
                    migrated,
                    "SELECT estimated_focus_minutes FROM todo_items WHERE id = 'event-long'"
                )
            )

            assertEquals(
                "原待办",
                scalarString(migrated, "SELECT title FROM todo_items WHERE id = 'shared-id'")
            )
            assertEquals(
                "不得覆盖",
                scalarString(migrated, "SELECT description FROM todo_items WHERE id = 'shared-id'")
            )
            assertEquals(
                9000L,
                scalarLong(
                    migrated,
                    "SELECT due_date_epoch_millis FROM todo_items WHERE id = 'shared-id'"
                )
            )

            val migratedConflictId = scalarString(
                migrated,
                "SELECT id FROM todo_items WHERE title = '冲突日程'"
            )
            assertFalse(migratedConflictId == "shared-id")
            assertEquals(
                "不应覆盖原待办",
                scalarString(
                    migrated,
                    "SELECT description FROM todo_items WHERE id = '$migratedConflictId'"
                )
            )
            assertEquals(
                23000000L,
                scalarLong(
                    migrated,
                    "SELECT scheduled_start_epoch_millis FROM todo_items " +
                        "WHERE id = '$migratedConflictId'"
                )
            )
            assertEquals(
                26600000L,
                scalarLong(
                    migrated,
                    "SELECT scheduled_end_epoch_millis FROM todo_items " +
                        "WHERE id = '$migratedConflictId'"
                )
            )
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.recreateLegacyVersion9LedgerTable() {
        execSQL("DROP TABLE ledger_entries")
        execSQL(
            """
            CREATE TABLE `ledger_entries` (
                `id` TEXT NOT NULL,
                `amount` INTEGER NOT NULL,
                `direction` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `note` TEXT,
                `occurred_at_epoch_millis` INTEGER NOT NULL,
                `source_note_id` TEXT,
                `isEstimated` INTEGER NOT NULL DEFAULT 0,
                `emotion` TEXT,
                `necessity` TEXT,
                `createdAtEpochMillis` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        execSQL("CREATE INDEX `index_ledger_entries_occurred_at_epoch_millis` ON `ledger_entries` (`occurred_at_epoch_millis`)")
        execSQL("CREATE INDEX `index_ledger_entries_direction` ON `ledger_entries` (`direction`)")
        execSQL("CREATE INDEX `index_ledger_entries_category` ON `ledger_entries` (`category`)")
        execSQL("CREATE INDEX `index_ledger_entries_source_note_id` ON `ledger_entries` (`source_note_id`)")
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertPlan(planId: String) {
        execSQL(
            "INSERT INTO supervision_plans " +
                "(plan_id, name, plan_type, enabled, zone_id, zone_mode, " +
                "active_days_mask, created_at_epoch_millis, updated_at_epoch_millis) " +
                "VALUES ('$planId', '测试计划', 'GLOBAL', 0, 'Asia/Shanghai', " +
                "'FOLLOW_DEVICE', 127, 1, 1)"
        )
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertVersion5ProductivityData() {
        execSQL(
            "INSERT INTO todo_items " +
                "(id, title, description, due_date_epoch_millis, priority, is_completed, " +
                "completed_at_epoch_millis, category, repeat_rule, associated_focus_plan_id, " +
                "supervision_lock_enabled, created_at_epoch_millis) VALUES " +
                "('todo-v5', '旧待办', '旧说明', 1704153600000, 3, 0, NULL, '工作', " +
                "'DAILY', 'focus-v5', 1, 1704067200000)"
        )
        execSQL(
            "INSERT INTO habit_items " +
                "(id, name, icon_res, color_hex, frequency_type, target_count_per_day, " +
                "current_streak, best_streak, is_archived, created_at_epoch_millis) VALUES " +
                "('habit-v5', '阅读', 'menu_book', '#4CAF50', 'WEEKLY_DAYS', 2, 3, 7, 0, " +
                "1704067200000)"
        )
        execSQL(
            "INSERT INTO habit_records " +
                "(id, habit_id, completed_date, note, reward_points, created_at_epoch_millis) " +
                "VALUES ('record-v5', 'habit-v5', '2024-01-02', '旧心得', 10, 1704153600000)"
        )
        execSQL(
            "INSERT INTO anniversary_items " +
                "(id, title, target_date_epoch_millis, is_lunar, type, repeat_rule, " +
                "is_pinned_top, show_on_widget, created_at_epoch_millis) VALUES " +
                "('anniversary-v5', '旧纪念日', 1704067200000, 0, 'COUNTDOWN', 'NONE', 1, 1, " +
                "1704067200000)"
        )
        execSQL(
            "INSERT INTO quick_notes " +
                "(id, content, media_uri, status, created_at_epoch_millis) VALUES " +
                "('note-v5', '旧闪念', 'content://legacy/image', 'RAW', 1704067200000)"
        )
    }

    private fun countRows(database: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Long =
        database.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private fun scalarLong(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        sql: String
    ): Long = database.query(sql).use { cursor ->
        cursor.moveToFirst()
        cursor.getLong(0)
    }

    private fun scalarString(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        sql: String
    ): String = database.query(sql).use { cursor ->
        cursor.moveToFirst()
        cursor.getString(0)
    }

    private fun scalarIsNull(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        sql: String
    ): Boolean = database.query(sql).use { cursor ->
        cursor.moveToFirst()
        cursor.isNull(0)
    }

    private fun hasColumn(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        column: String
    ): Boolean = database.query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return@use true
        }
        false
    }

    private fun hasColumnDefault(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        column: String
    ): Boolean = database.query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) {
                return@use !cursor.isNull(defaultIndex)
            }
        }
        false
    }

    private fun hasTable(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String
    ): Boolean = database.query(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(table)
    ).use { cursor ->
        cursor.moveToFirst()
    }

    private fun hasIndex(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        index: String
    ): Boolean = database.query(
        "SELECT 1 FROM sqlite_master " +
            "WHERE type = 'index' AND tbl_name = ? AND name = ?",
        arrayOf(table, index)
    ).use { cursor ->
        cursor.moveToFirst()
    }

    private fun foreignKeyCount(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String
    ): Long = database.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
        cursor.count.toLong()
    }

    private fun foreignKeyDeleteAction(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String
    ): String = database.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
        check(cursor.moveToFirst()) { "外键不存在" }
        cursor.getString(cursor.getColumnIndexOrThrow("on_delete"))
    }

    private companion object {
        const val DATABASE_NAME = "history-migration-test.db"

        val VERSION_6_TABLES = listOf(
            "todo_subtasks",
            "anniversary_occurrences",
            "quick_note_conversions",
            "achievement_unlocks",
            "commitment_policies",
            "commitment_blocked_apps",
            "commitment_occurrences",
            "productivity_reward_outbox",
            "celebration_events"
        )
    }
}
