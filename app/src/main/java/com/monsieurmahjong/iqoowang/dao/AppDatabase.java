package com.monsieurmahjong.iqoowang.dao;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {Expense.class},
        version = 2,
        exportSchema = false // 严格对应原代码配置
)
public abstract class AppDatabase extends RoomDatabase {
    public abstract ExpenseDao expenseDao();

    // 对应Kotlin的@Volatile INSTANCE变量
    private static volatile AppDatabase INSTANCE;

    /**
     * v1 → v2：新增位置信息三列（摇一摇/NFC记账时自动定位用，见 Expense.java）。
     * 三列都不加 NOT NULL：老账单没有定位数据，新账单如果定位失败也允许为空，
     * SQLite 的 ADD COLUMN 不写 NOT NULL 时默认就是可空列，不需要额外给 DEFAULT 值。
     * 没有配 fallbackToDestructiveMigration()，所以这一步是必须的——
     * 版本号涨了但没给迁移路径，Room 会直接崩溃而不是静默清空数据库。
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE expense_table ADD COLUMN latitude REAL");
            database.execSQL("ALTER TABLE expense_table ADD COLUMN longitude REAL");
            database.execSQL("ALTER TABLE expense_table ADD COLUMN locationName TEXT");
        }
    };

    // 对应Kotlin companion object的getDatabase方法
    public static AppDatabase getDatabase(Context context) {
        // 完全相同的双重检查锁单例逻辑
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "expense_db" // 严格对应原代码的数据库名称
                    ).addMigrations(MIGRATION_1_2).build();
                }
            }
        }
        return INSTANCE;
    }
}
