package com.example.controlfree.supervision.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.controlfree.growth.GrowthAccountEntity
import com.example.controlfree.growth.GrowthCycleResultEntity
import com.example.controlfree.growth.GrowthDao
import com.example.controlfree.growth.GrowthLedgerEntity
import com.example.controlfree.growth.GrowthUnlockOrderEntity
import com.example.controlfree.todo.TodoItemEntity
import com.example.controlfree.todo.HabitItemEntity
import com.example.controlfree.todo.HabitRecordEntity
import com.example.controlfree.todo.AnniversaryItemEntity
import com.example.controlfree.todo.QuickNoteEntity
import com.example.controlfree.todo.TodoDao
import com.example.controlfree.todo.HabitDao
import com.example.controlfree.todo.AnniversaryDao
import com.example.controlfree.todo.QuickNoteDao
import com.example.controlfree.todo.TodoSubtaskEntity
import com.example.controlfree.todo.AnniversaryOccurrenceEntity
import com.example.controlfree.todo.QuickNoteConversionEntity
import com.example.controlfree.todo.LedgerEntryEntity
import com.example.controlfree.todo.LedgerDao
import com.example.controlfree.todo.AchievementUnlockEntity
import com.example.controlfree.todo.CommitmentPolicyEntity
import com.example.controlfree.todo.CommitmentBlockedAppEntity
import com.example.controlfree.todo.CommitmentOccurrenceEntity
import com.example.controlfree.todo.ProductivityRewardEventEntity
import com.example.controlfree.todo.CelebrationEventEntity
import com.example.controlfree.todo.ProductivityEventDao
import com.example.controlfree.todo.CommitmentDao
import com.example.controlfree.todo.TimeBlockEventDao
import com.example.controlfree.todo.TimeBlockEventEntity
import java.util.UUID

@Database(
    entities = [
        SupervisionPlanEntity::class,
        SupervisionTimeRangeEntity::class,
        GlobalSupervisionPolicyEntity::class,
        AppSupervisionPolicyEntity::class,
        AppSupervisionDisabledTimeRangeEntity::class,
        SupervisionPlanTriggerAppEntity::class,
        PlanActivationReservationEntity::class,
        SupervisionSessionEntity::class,
        SupervisionHistoryEventEntity::class,
        GrowthAccountEntity::class,
        GrowthLedgerEntity::class,
        GrowthCycleResultEntity::class,
        GrowthUnlockOrderEntity::class,
        TodoItemEntity::class,
        HabitItemEntity::class,
        HabitRecordEntity::class,
        AnniversaryItemEntity::class,
        QuickNoteEntity::class,
        TodoSubtaskEntity::class,
        AnniversaryOccurrenceEntity::class,
        QuickNoteConversionEntity::class,
        AchievementUnlockEntity::class,
        CommitmentPolicyEntity::class,
        CommitmentBlockedAppEntity::class,
        CommitmentOccurrenceEntity::class,
        ProductivityRewardEventEntity::class,
        CelebrationEventEntity::class,
        LedgerEntryEntity::class,
        TimeBlockEventEntity::class
    ],
    version = 17,
    exportSchema = true
)
abstract class ControlFreeDatabase : RoomDatabase() {
    abstract fun supervisionPlanDao(): SupervisionPlanDao
    abstract fun supervisionHistoryDao(): SupervisionHistoryDao
    abstract fun growthDao(): GrowthDao
    abstract fun todoDao(): TodoDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun habitDao(): HabitDao
    abstract fun anniversaryDao(): AnniversaryDao
    abstract fun quickNoteDao(): QuickNoteDao
    abstract fun productivityEventDao(): ProductivityEventDao
    abstract fun commitmentDao(): CommitmentDao
    abstract fun timeBlockEventDao(): TimeBlockEventDao

    companion object {
        private const val DATABASE_NAME = "controlfree.db"

        @Volatile
        private var instance: ControlFreeDatabase? = null

        fun getInstance(context: Context): ControlFreeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ControlFreeDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17
                    )
                    .build()
                    .also { instance = it }
            }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `supervision_sessions` (
                        `session_id` TEXT NOT NULL,
                        `identity_key` TEXT NOT NULL,
                        `runtime_slot` TEXT,
                        `session_kind` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `plan_id` TEXT,
                        `package_name` TEXT,
                        `usage_minutes` INTEGER,
                        `lock_minutes` INTEGER,
                        `started_at_epoch_millis` INTEGER NOT NULL,
                        `ended_at_epoch_millis` INTEGER,
                        `end_reason` TEXT,
                        `updated_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`session_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_supervision_sessions_runtime_slot` " +
                        "ON `supervision_sessions` (`runtime_slot`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_supervision_sessions_started_at_epoch_millis` " +
                        "ON `supervision_sessions` (`started_at_epoch_millis`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_supervision_sessions_session_kind_started_at_epoch_millis` " +
                        "ON `supervision_sessions` (`session_kind`, `started_at_epoch_millis`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_supervision_sessions_plan_id` " +
                        "ON `supervision_sessions` (`plan_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_supervision_sessions_package_name` " +
                        "ON `supervision_sessions` (`package_name`)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `plan_activation_reservations` (
                        `plan_id` TEXT NOT NULL,
                        `enable_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`plan_id`),
                        FOREIGN KEY(`plan_id`) REFERENCES `supervision_plans`(`plan_id`)
                            ON UPDATE CASCADE ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `growth_accounts` (
                        `account_id` TEXT NOT NULL,
                        `balance_points` INTEGER NOT NULL,
                        `reserved_points` INTEGER NOT NULL,
                        `lifetime_experience` INTEGER NOT NULL,
                        `lifetime_spent_points` INTEGER NOT NULL,
                        `revision` INTEGER NOT NULL,
                        `created_at_epoch_millis` INTEGER NOT NULL,
                        `updated_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`account_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `growth_ledger` (
                        `ledger_id` TEXT NOT NULL,
                        `account_id` TEXT NOT NULL,
                        `source_key` TEXT NOT NULL,
                        `entry_type` TEXT NOT NULL,
                        `reason` TEXT NOT NULL,
                        `balance_delta_points` INTEGER NOT NULL,
                        `reserved_delta_points` INTEGER NOT NULL,
                        `experience_delta` INTEGER NOT NULL,
                        `spending_delta_points` INTEGER NOT NULL,
                        `counts_toward_daily_cap` INTEGER NOT NULL,
                        `related_cycle_id` TEXT,
                        `related_order_id` TEXT,
                        `policy_version` INTEGER NOT NULL,
                        `occurred_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`ledger_id`),
                        FOREIGN KEY(`account_id`) REFERENCES `growth_accounts`(`account_id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_growth_ledger_source_key` " +
                        "ON `growth_ledger` (`source_key`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_growth_ledger_account_id_occurred_at_epoch_millis` " +
                        "ON `growth_ledger` (`account_id`, `occurred_at_epoch_millis`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_growth_ledger_related_cycle_id` " +
                        "ON `growth_ledger` (`related_cycle_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_growth_ledger_related_order_id` " +
                        "ON `growth_ledger` (`related_order_id`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `growth_cycle_outcomes` (
                        `cycle_id` TEXT NOT NULL,
                        `account_id` TEXT NOT NULL,
                        `run_id` TEXT NOT NULL,
                        `cycle_ordinal` INTEGER NOT NULL,
                        `mode` TEXT NOT NULL,
                        `configured_duration_seconds` INTEGER NOT NULL,
                        `valid_elapsed_seconds` INTEGER NOT NULL,
                        `paused_seconds` INTEGER NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `awarded_experience_points` INTEGER NOT NULL,
                        `credited_balance_points` INTEGER NOT NULL,
                        `ledger_source_key` TEXT,
                        `recorded_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`cycle_id`),
                        FOREIGN KEY(`account_id`) REFERENCES `growth_accounts`(`account_id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_growth_cycle_outcomes_account_id` " +
                        "ON `growth_cycle_outcomes` (`account_id`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_growth_cycle_outcomes_run_id_cycle_ordinal` " +
                        "ON `growth_cycle_outcomes` (`run_id`, `cycle_ordinal`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_growth_cycle_outcomes_ledger_source_key` " +
                        "ON `growth_cycle_outcomes` (`ledger_source_key`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `growth_unlock_orders` (
                        `order_id` TEXT NOT NULL,
                        `account_id` TEXT NOT NULL,
                        `lock_session_id` TEXT NOT NULL,
                        `action_kind` TEXT NOT NULL,
                        `pause_duration_minutes` INTEGER NOT NULL,
                        `authentication_kind` TEXT NOT NULL,
                        `authentication_id` TEXT NOT NULL,
                        `nominal_cost_points` INTEGER NOT NULL,
                        `reserved_cost_points` INTEGER NOT NULL,
                        `cost_waived` INTEGER NOT NULL,
                        `state` TEXT NOT NULL,
                        `policy_version` INTEGER NOT NULL,
                        `prepared_at_epoch_millis` INTEGER NOT NULL,
                        `expires_at_epoch_millis` INTEGER NOT NULL,
                        `finalized_at_epoch_millis` INTEGER,
                        PRIMARY KEY(`order_id`),
                        FOREIGN KEY(`account_id`) REFERENCES `growth_accounts`(`account_id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_growth_unlock_orders_account_id_state` " +
                        "ON `growth_unlock_orders` (`account_id`, `state`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_growth_unlock_orders_lock_session_id_action_kind` " +
                        "ON `growth_unlock_orders` (`lock_session_id`, `action_kind`)"
                )

                // 既有安装升级后立即拥有初始成长值；初始赠送不计入终身成长经验。
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `growth_accounts` (
                        `account_id`, `balance_points`, `reserved_points`, `lifetime_experience`,
                        `lifetime_spent_points`, `revision`, `created_at_epoch_millis`,
                        `updated_at_epoch_millis`
                    ) VALUES (
                        'default', 60, 0, 0, 0, 0,
                        CAST(strftime('%s', 'now') AS INTEGER) * 1000,
                        CAST(strftime('%s', 'now') AS INTEGER) * 1000
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `growth_ledger` (
                        `ledger_id`, `account_id`, `source_key`, `entry_type`, `reason`,
                        `balance_delta_points`, `reserved_delta_points`, `experience_delta`,
                        `spending_delta_points`, `counts_toward_daily_cap`, `related_cycle_id`,
                        `related_order_id`, `policy_version`, `occurred_at_epoch_millis`
                    ) VALUES (
                        'initial-growth-grant-v1', 'default', 'account:default:initial:v1',
                        'initial_grant', 'installation_grant', 60, 0, 0, 0, 0, NULL, NULL, 1,
                        CAST(strftime('%s', 'now') AS INTEGER) * 1000
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `todo_items` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `description` TEXT,
                        `due_date_epoch_millis` INTEGER,
                        `priority` INTEGER NOT NULL,
                        `is_completed` INTEGER NOT NULL,
                        `completed_at_epoch_millis` INTEGER,
                        `category` TEXT NOT NULL,
                        `repeat_rule` TEXT,
                        `associated_focus_plan_id` TEXT,
                        `supervision_lock_enabled` INTEGER NOT NULL,
                        `created_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_todo_items_due_date_epoch_millis` ON `todo_items` (`due_date_epoch_millis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_todo_items_is_completed` ON `todo_items` (`is_completed`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_todo_items_priority` ON `todo_items` (`priority`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `habit_items` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `icon_res` TEXT NOT NULL,
                        `color_hex` TEXT NOT NULL,
                        `frequency_type` TEXT NOT NULL,
                        `target_count_per_day` INTEGER NOT NULL,
                        `current_streak` INTEGER NOT NULL,
                        `best_streak` INTEGER NOT NULL,
                        `is_archived` INTEGER NOT NULL,
                        `created_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_habit_items_is_archived` ON `habit_items` (`is_archived`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `habit_records` (
                        `id` TEXT NOT NULL,
                        `habit_id` TEXT NOT NULL,
                        `completed_date` TEXT NOT NULL,
                        `note` TEXT,
                        `reward_points` INTEGER NOT NULL,
                        `created_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_habit_records_habit_id_completed_date` ON `habit_records` (`habit_id`, `completed_date`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `anniversary_items` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `target_date_epoch_millis` INTEGER NOT NULL,
                        `is_lunar` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `repeat_rule` TEXT NOT NULL,
                        `is_pinned_top` INTEGER NOT NULL,
                        `show_on_widget` INTEGER NOT NULL,
                        `created_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_anniversary_items_target_date_epoch_millis` ON `anniversary_items` (`target_date_epoch_millis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_anniversary_items_is_pinned_top` ON `anniversary_items` (`is_pinned_top`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `quick_notes` (
                        `id` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `media_uri` TEXT,
                        `status` TEXT NOT NULL,
                        `created_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_quick_notes_status` ON `quick_notes` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_quick_notes_created_at_epoch_millis` ON `quick_notes` (`created_at_epoch_millis`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateTodoItemsToV6(db)
                migrateHabitsToV6(db)
                migrateAnniversariesToV6(db)
                migrateQuickNotesToV6(db)
                createProductivityV6Tables(db)
            }
        }

        /** 为 AI 建议生成的一次性专注锁增加绝对时间窗；旧周计划保持为空。 */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `supervision_plans` " +
                        "ADD COLUMN `one_time_start_epoch_millis` INTEGER"
                )
                db.execSQL(
                    "ALTER TABLE `supervision_plans` " +
                        "ADD COLUMN `one_time_end_epoch_millis` INTEGER"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_supervision_plans_one_time_start_epoch_millis` " +
                        "ON `supervision_plans` (`one_time_start_epoch_millis`)"
                )
            }
        }

        /** 为待办与专注计划的关联查询建立索引，保留 v7 中已有的全部待办数据。 */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_todo_items_associated_focus_plan_id` " +
                        "ON `todo_items` (`associated_focus_plan_id`)"
                )
            }
        }

        private fun migrateTodoItemsToV6(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `todo_items` ADD COLUMN `scheduled_start_epoch_millis` INTEGER")
            db.execSQL("ALTER TABLE `todo_items` ADD COLUMN `scheduled_end_epoch_millis` INTEGER")
            db.execSQL("ALTER TABLE `todo_items` ADD COLUMN `estimated_focus_minutes` INTEGER")
            db.execSQL("ALTER TABLE `todo_items` ADD COLUMN `is_important` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `todo_items` ADD COLUMN `urgency_mode` TEXT NOT NULL DEFAULT 'AUTO'")
            db.execSQL("ALTER TABLE `todo_items` ADD COLUMN `recurrence_type` TEXT NOT NULL DEFAULT 'NONE'")
            db.execSQL("ALTER TABLE `todo_items` ADD COLUMN `recurrence_interval` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE `todo_items` ADD COLUMN `recurrence_days_mask` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `todo_items` ADD COLUMN `recurrence_day_of_month` INTEGER")
            db.execSQL("ALTER TABLE `todo_items` ADD COLUMN `recurrence_series_id` TEXT")
            db.execSQL("ALTER TABLE `todo_items` ADD COLUMN `recurrence_sequence` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `todo_items` ADD COLUMN `updated_at_epoch_millis` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `todo_items` SET `is_important` = CASE WHEN `priority` >= 2 THEN 1 ELSE 0 END")
            db.execSQL("UPDATE `todo_items` SET `urgency_mode` = CASE WHEN `priority` = 3 THEN 'URGENT' ELSE 'AUTO' END")
            db.execSQL(
                "UPDATE `todo_items` SET `recurrence_type` = UPPER(TRIM(`repeat_rule`)) " +
                    "WHERE UPPER(TRIM(`repeat_rule`)) IN " +
                    "('DAILY', 'WEEKDAYS', 'WEEKLY_DAYS', 'MONTHLY_DAY', 'COMPLETION_INTERVAL')"
            )
            db.execSQL("UPDATE `todo_items` SET `updated_at_epoch_millis` = `created_at_epoch_millis`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_todo_items_scheduled_start_epoch_millis` ON `todo_items` (`scheduled_start_epoch_millis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_todo_items_category` ON `todo_items` (`category`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_todo_items_recurrence_series_id_recurrence_sequence` ON `todo_items` (`recurrence_series_id`, `recurrence_sequence`)")
        }

        private fun migrateHabitsToV6(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `habit_items` ADD COLUMN `weekdays_mask` INTEGER NOT NULL DEFAULT 127")
            db.execSQL("ALTER TABLE `habit_items` ADD COLUMN `weekly_target_days` INTEGER NOT NULL DEFAULT 3")
            db.execSQL("ALTER TABLE `habit_items` ADD COLUMN `interval_days` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE `habit_items` ADD COLUMN `start_date` TEXT NOT NULL DEFAULT '1970-01-01'")
            db.execSQL("ALTER TABLE `habit_items` ADD COLUMN `updated_at_epoch_millis` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `habit_items` SET `frequency_type` = 'SPECIFIC_WEEKDAYS' WHERE `frequency_type` = 'WEEKLY_DAYS'")
            db.execSQL("UPDATE `habit_items` SET `start_date` = date(`created_at_epoch_millis` / 1000, 'unixepoch')")
            db.execSQL("UPDATE `habit_items` SET `updated_at_epoch_millis` = `created_at_epoch_millis`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_habit_items_frequency_type` ON `habit_items` (`frequency_type`)")

            db.execSQL("ALTER TABLE `habit_records` ADD COLUMN `completion_count` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE `habit_records` ADD COLUMN `is_backfill` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `habit_records` ADD COLUMN `updated_at_epoch_millis` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `habit_records` SET `updated_at_epoch_millis` = `created_at_epoch_millis`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_habit_records_completed_date` ON `habit_records` (`completed_date`)")
        }

        private fun migrateAnniversariesToV6(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `anniversary_items` ADD COLUMN `source_year` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `anniversary_items` ADD COLUMN `source_month` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `anniversary_items` ADD COLUMN `source_day` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `anniversary_items` ADD COLUMN `source_hour` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `anniversary_items` ADD COLUMN `source_minute` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `anniversary_items` ADD COLUMN `source_second` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `anniversary_items` ADD COLUMN `is_lunar_leap_month` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `anniversary_items` ADD COLUMN `zone_id` TEXT NOT NULL DEFAULT 'Asia/Shanghai'")
            db.execSQL("ALTER TABLE `anniversary_items` ADD COLUMN `show_on_lock_screen` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `anniversary_items` ADD COLUMN `updated_at_epoch_millis` INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "UPDATE `anniversary_items` SET " +
                    "`source_year` = CAST(strftime('%Y', `target_date_epoch_millis` / 1000, 'unixepoch', '+8 hours') AS INTEGER), " +
                    "`source_month` = CAST(strftime('%m', `target_date_epoch_millis` / 1000, 'unixepoch', '+8 hours') AS INTEGER), " +
                    "`source_day` = CAST(strftime('%d', `target_date_epoch_millis` / 1000, 'unixepoch', '+8 hours') AS INTEGER), " +
                    "`source_hour` = CAST(strftime('%H', `target_date_epoch_millis` / 1000, 'unixepoch', '+8 hours') AS INTEGER), " +
                    "`source_minute` = CAST(strftime('%M', `target_date_epoch_millis` / 1000, 'unixepoch', '+8 hours') AS INTEGER), " +
                    "`source_second` = CAST(strftime('%S', `target_date_epoch_millis` / 1000, 'unixepoch', '+8 hours') AS INTEGER), " +
                    "`updated_at_epoch_millis` = `created_at_epoch_millis`"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anniversary_items_show_on_widget` ON `anniversary_items` (`show_on_widget`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anniversary_items_show_on_lock_screen` ON `anniversary_items` (`show_on_lock_screen`)")
        }

        private fun migrateQuickNotesToV6(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `quick_notes` ADD COLUMN `media_mime_type` TEXT")
            db.execSQL("ALTER TABLE `quick_notes` ADD COLUMN `media_display_name` TEXT")
            db.execSQL("ALTER TABLE `quick_notes` ADD COLUMN `media_size_bytes` INTEGER")
            db.execSQL("ALTER TABLE `quick_notes` ADD COLUMN `capture_source` TEXT NOT NULL DEFAULT 'TEXT'")
            db.execSQL("ALTER TABLE `quick_notes` ADD COLUMN `updated_at_epoch_millis` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `quick_notes` SET `updated_at_epoch_millis` = `created_at_epoch_millis`")
        }

        private fun createProductivityV6Tables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `todo_subtasks` (
                    `id` TEXT NOT NULL,
                    `todo_id` TEXT NOT NULL,
                    `parent_subtask_id` TEXT,
                    `title` TEXT NOT NULL,
                    `is_completed` INTEGER NOT NULL,
                    `completed_at_epoch_millis` INTEGER,
                    `sort_order` INTEGER NOT NULL,
                    `created_at_epoch_millis` INTEGER NOT NULL,
                    `updated_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_todo_subtasks_todo_id` ON `todo_subtasks` (`todo_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_todo_subtasks_parent_subtask_id` ON `todo_subtasks` (`parent_subtask_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_todo_subtasks_todo_id_sort_order` ON `todo_subtasks` (`todo_id`, `sort_order`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `anniversary_occurrences` (
                    `id` TEXT NOT NULL,
                    `anniversary_id` TEXT NOT NULL,
                    `occurrence_key` TEXT NOT NULL,
                    `occurs_at_epoch_millis` INTEGER NOT NULL,
                    `recorded_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_anniversary_occurrences_anniversary_id_occurrence_key` ON `anniversary_occurrences` (`anniversary_id`, `occurrence_key`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anniversary_occurrences_occurs_at_epoch_millis` ON `anniversary_occurrences` (`occurs_at_epoch_millis`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `quick_note_conversions` (
                    `note_id` TEXT NOT NULL,
                    `target_type` TEXT NOT NULL,
                    `target_id` TEXT NOT NULL,
                    `created_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`note_id`, `target_type`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_quick_note_conversions_target_id` ON `quick_note_conversions` (`target_id`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `achievement_unlocks` (
                    `id` TEXT NOT NULL,
                    `achievement_type` TEXT NOT NULL,
                    `subject_id` TEXT,
                    `tier` INTEGER NOT NULL,
                    `title` TEXT NOT NULL,
                    `description` TEXT NOT NULL,
                    `unlocked_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_achievement_unlocks_achievement_type_subject_id` ON `achievement_unlocks` (`achievement_type`, `subject_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_achievement_unlocks_unlocked_at_epoch_millis` ON `achievement_unlocks` (`unlocked_at_epoch_millis`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `commitment_policies` (
                    `id` TEXT NOT NULL,
                    `source_type` TEXT NOT NULL,
                    `source_id` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `local_deadline_minute` INTEGER,
                    `grace_minutes` INTEGER NOT NULL,
                    `max_lock_minutes` INTEGER NOT NULL,
                    `zone_id` TEXT NOT NULL,
                    `created_at_epoch_millis` INTEGER NOT NULL,
                    `updated_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_commitment_policies_source_type_source_id` ON `commitment_policies` (`source_type`, `source_id`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `commitment_blocked_apps` (
                    `policy_id` TEXT NOT NULL,
                    `package_name` TEXT NOT NULL,
                    PRIMARY KEY(`policy_id`, `package_name`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_commitment_blocked_apps_package_name` ON `commitment_blocked_apps` (`package_name`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `commitment_occurrences` (
                    `id` TEXT NOT NULL,
                    `policy_id` TEXT NOT NULL,
                    `occurrence_key` TEXT NOT NULL,
                    `deadline_epoch_millis` INTEGER NOT NULL,
                    `expires_at_epoch_millis` INTEGER NOT NULL,
                    `activated_at_epoch_millis` INTEGER,
                    `satisfied_at_epoch_millis` INTEGER,
                    `created_at_epoch_millis` INTEGER NOT NULL,
                    `updated_at_epoch_millis` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_commitment_occurrences_policy_id_occurrence_key` ON `commitment_occurrences` (`policy_id`, `occurrence_key`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_commitment_occurrences_deadline_epoch_millis` ON `commitment_occurrences` (`deadline_epoch_millis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_commitment_occurrences_satisfied_at_epoch_millis` ON `commitment_occurrences` (`satisfied_at_epoch_millis`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `productivity_reward_outbox` (
                    `id` TEXT NOT NULL,
                    `source_key` TEXT NOT NULL,
                    `reward_type` TEXT NOT NULL,
                    `subject_id` TEXT NOT NULL,
                    `balance_points` INTEGER NOT NULL,
                    `experience_points` INTEGER NOT NULL,
                    `local_date` TEXT NOT NULL,
                    `created_at_epoch_millis` INTEGER NOT NULL,
                    `processed_at_epoch_millis` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_productivity_reward_outbox_source_key` ON `productivity_reward_outbox` (`source_key`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_productivity_reward_outbox_processed_at_epoch_millis` ON `productivity_reward_outbox` (`processed_at_epoch_millis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_productivity_reward_outbox_reward_type_local_date` ON `productivity_reward_outbox` (`reward_type`, `local_date`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `celebration_events` (
                    `id` TEXT NOT NULL,
                    `source_key` TEXT NOT NULL,
                    `celebration_type` TEXT NOT NULL,
                    `subject_id` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `message` TEXT NOT NULL,
                    `occurred_at_epoch_millis` INTEGER NOT NULL,
                    `consumed_at_epoch_millis` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_celebration_events_source_key` ON `celebration_events` (`source_key`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_celebration_events_consumed_at_epoch_millis` ON `celebration_events` (`consumed_at_epoch_millis`)")
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `ledger_entries` (
                        `id` TEXT NOT NULL, 
                        `amount` INTEGER NOT NULL, 
                        `direction` TEXT NOT NULL, 
                        `category` TEXT NOT NULL, 
                        `title` TEXT NOT NULL, 
                        `note` TEXT, 
                        `occurred_at_epoch_millis` INTEGER NOT NULL, 
                        `source_note_id` TEXT, 
                        `isEstimated` INTEGER NOT NULL,
                        `emotion` TEXT,
                        `necessity` TEXT,
                        `createdAtEpochMillis` INTEGER NOT NULL, 
                        `updatedAtEpochMillis` INTEGER NOT NULL, 
                        PRIMARY KEY(`id`)
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_entries_occurred_at_epoch_millis` ON `ledger_entries` (`occurred_at_epoch_millis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_entries_direction` ON `ledger_entries` (`direction`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_entries_category` ON `ledger_entries` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_entries_source_note_id` ON `ledger_entries` (`source_note_id`)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v9 的首个发布版本曾通过 MIGRATION_8_9 创建 `isEstimated DEFAULT 0`，
                // 而导出的 v9 schema 没有该默认值。SQLite 的 ALTER TABLE 无法移除已有
                // 默认值，因此这里重建整张表，兼容两种历史 v9 结构并让 Room 的最终
                // schema 校验稳定通过。
                // v9 没有这些业务校验。迁移只补齐 v10 新列，旧字段逐值保留，
                // 避免数据库升级过程擅自改写用户账目；旧估算记录补一条可解码警告。
                db.execSQL("ALTER TABLE `ledger_entries` RENAME TO `ledger_entries_v9`")
                db.execSQL(
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
                        `isEstimated` INTEGER NOT NULL,
                        `emotion` TEXT,
                        `necessity` TEXT,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        `ai_confidence` REAL NOT NULL DEFAULT 0,
                        `source_type` TEXT NOT NULL DEFAULT 'AI',
                        `ai_warnings_json` TEXT,
                        `source_batch_id` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `ledger_entries` (
                        `id`, `amount`, `direction`, `category`, `title`, `note`,
                        `occurred_at_epoch_millis`, `source_note_id`, `isEstimated`,
                        `emotion`, `necessity`, `createdAtEpochMillis`, `updatedAtEpochMillis`,
                        `ai_confidence`, `source_type`, `ai_warnings_json`, `source_batch_id`
                    )
                    SELECT
                        `id`,
                        `amount`,
                        `direction`,
                        `category`,
                        `title`,
                        `note`,
                        `occurred_at_epoch_millis`,
                        `source_note_id`,
                        `isEstimated`,
                        `emotion`,
                        `necessity`,
                        `createdAtEpochMillis`,
                        `updatedAtEpochMillis`,
                        0,
                        'AI',
                        CASE
                            WHEN `isEstimated` != 0 THEN
                                '["由旧版本迁移，原始估算警告不可用"]'
                            ELSE NULL
                        END,
                        NULL
                    FROM `ledger_entries_v9`
                    """.trimIndent()
                )
                // 删除旧表会同时删除被重命名的旧索引，之后再按 v10 schema 重建。
                db.execSQL("DROP TABLE `ledger_entries_v9`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_entries_occurred_at_epoch_millis` ON `ledger_entries` (`occurred_at_epoch_millis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_entries_direction` ON `ledger_entries` (`direction`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_entries_category` ON `ledger_entries` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_entries_source_note_id` ON `ledger_entries` (`source_note_id`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ledger_entries_source_batch_id` " +
                        "ON `ledger_entries` (`source_batch_id`)"
                )
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `todo_items` ADD COLUMN `reminders_json` TEXT")
                db.execSQL("ALTER TABLE `anniversary_items` ADD COLUMN `reminders_json` TEXT")
                db.execSQL("ALTER TABLE `habit_items` ADD COLUMN `reminders_json` TEXT")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `supervision_history_events` (
                        `event_id` TEXT NOT NULL,
                        `event_type` TEXT NOT NULL,
                        `boot_instance_key` TEXT NOT NULL,
                        `boot_count` INTEGER,
                        `occurred_at_epoch_millis` INTEGER NOT NULL,
                        `received_at_epoch_millis` INTEGER NOT NULL,
                        `session_id` TEXT,
                        `runtime_slot` TEXT,
                        `recovery_status` TEXT NOT NULL,
                        `updated_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`event_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_supervision_history_events_boot_instance_key` " +
                        "ON `supervision_history_events` (`boot_instance_key`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_supervision_history_events_occurred_at_epoch_millis` " +
                        "ON `supervision_history_events` (`occurred_at_epoch_millis`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_supervision_history_events_event_type_occurred_at_epoch_millis` " +
                        "ON `supervision_history_events` " +
                        "(`event_type`, `occurred_at_epoch_millis`)"
                )
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `quick_notes` ADD COLUMN `ai_advice` TEXT")
                db.execSQL("ALTER TABLE `quick_notes` ADD COLUMN `ai_assistant_fingerprint` TEXT")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `time_block_events` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `description` TEXT,
                        `start_at_epoch_millis` INTEGER NOT NULL,
                        `end_at_epoch_millis` INTEGER NOT NULL,
                        `project` TEXT NOT NULL,
                        `priority` INTEGER NOT NULL,
                        `is_completed` INTEGER NOT NULL,
                        `completed_at_epoch_millis` INTEGER,
                        `created_at_epoch_millis` INTEGER NOT NULL,
                        `updated_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_time_block_events_start_at_epoch_millis` " +
                        "ON `time_block_events` (`start_at_epoch_millis`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_time_block_events_end_at_epoch_millis` " +
                        "ON `time_block_events` (`end_at_epoch_millis`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_time_block_events_is_completed` " +
                        "ON `time_block_events` (`is_completed`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_time_block_events_project` " +
                        "ON `time_block_events` (`project`)"
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val conflictingEventIds = buildList {
                    db.query(
                        """
                        SELECT `time_block_events`.`id`
                        FROM `time_block_events`
                        INNER JOIN `todo_items`
                            ON `todo_items`.`id` = `time_block_events`.`id`
                        """.trimIndent()
                    ).use { cursor ->
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                }
                conflictingEventIds.forEach { eventId ->
                    var migratedId: String
                    do {
                        migratedId = UUID.randomUUID().toString()
                        val idAlreadyExists = db.query(
                            """
                            SELECT 1 FROM `todo_items` WHERE `id` = ?
                            UNION ALL
                            SELECT 1 FROM `time_block_events` WHERE `id` = ?
                            LIMIT 1
                            """.trimIndent(),
                            arrayOf(migratedId, migratedId)
                        ).use { it.moveToFirst() }
                    } while (idAlreadyExists)
                    db.execSQL(
                        "UPDATE `time_block_events` SET `id` = ? WHERE `id` = ?",
                        arrayOf(migratedId, eventId)
                    )
                }
                db.execSQL(
                    """
                    INSERT INTO `todo_items` (
                        `id`,
                        `title`,
                        `description`,
                        `due_date_epoch_millis`,
                        `priority`,
                        `is_completed`,
                        `completed_at_epoch_millis`,
                        `category`,
                        `repeat_rule`,
                        `associated_focus_plan_id`,
                        `supervision_lock_enabled`,
                        `created_at_epoch_millis`,
                        `scheduled_start_epoch_millis`,
                        `scheduled_end_epoch_millis`,
                        `estimated_focus_minutes`,
                        `is_important`,
                        `urgency_mode`,
                        `recurrence_type`,
                        `recurrence_interval`,
                        `recurrence_days_mask`,
                        `recurrence_day_of_month`,
                        `recurrence_series_id`,
                        `recurrence_sequence`,
                        `updated_at_epoch_millis`,
                        `reminders_json`
                    )
                    SELECT
                        `id`,
                        `title`,
                        `description`,
                        NULL,
                        `priority`,
                        `is_completed`,
                        `completed_at_epoch_millis`,
                        `project`,
                        NULL,
                        NULL,
                        0,
                        `created_at_epoch_millis`,
                        `start_at_epoch_millis`,
                        `end_at_epoch_millis`,
                        MAX(
                            1,
                            MIN(
                                180,
                                CAST(
                                    (`end_at_epoch_millis` - `start_at_epoch_millis`) / 60000
                                    AS INTEGER
                                )
                            )
                        ),
                        CASE WHEN `priority` >= 2 THEN 1 ELSE 0 END,
                        'AUTO',
                        'NONE',
                        1,
                        0,
                        NULL,
                        NULL,
                        0,
                        `updated_at_epoch_millis`,
                        NULL
                    FROM `time_block_events`
                    """.trimIndent()
                )
                db.execSQL("DELETE FROM `time_block_events`")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `app_supervision_policies` " +
                        "ADD COLUMN `daily_usage_limit_minutes` INTEGER NOT NULL DEFAULT 1440"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_supervision_disabled_time_ranges` (
                        `plan_id` TEXT NOT NULL,
                        `start_minute` INTEGER NOT NULL,
                        `end_minute_exclusive` INTEGER NOT NULL,
                        PRIMARY KEY(`plan_id`, `start_minute`, `end_minute_exclusive`),
                        FOREIGN KEY(`plan_id`) REFERENCES `supervision_plans`(`plan_id`)
                            ON UPDATE CASCADE ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_app_supervision_disabled_time_ranges_plan_id` " +
                        "ON `app_supervision_disabled_time_ranges` (`plan_id`)"
                )
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `supervision_plan_trigger_apps` (
                        `plan_id` TEXT NOT NULL,
                        `package_name` TEXT NOT NULL,
                        PRIMARY KEY(`plan_id`, `package_name`),
                        FOREIGN KEY(`plan_id`) REFERENCES `supervision_plans`(`plan_id`)
                            ON UPDATE CASCADE ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_supervision_plan_trigger_apps_plan_id` " +
                        "ON `supervision_plan_trigger_apps` (`plan_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_supervision_plan_trigger_apps_package_name` " +
                        "ON `supervision_plan_trigger_apps` (`package_name`)"
                )
            }
        }
    }
}
