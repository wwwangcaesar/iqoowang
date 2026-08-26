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
        version = 3,
        exportSchema = false // 严格对应原代码配置
)
public abstract class AppDatabase extends RoomDatabase {
    public abstract ExpenseDao expenseDao();

    // 对应Kotlin的@Volatile INSTANCE变量
    private static volatile AppDatabase INSTANCE;

    /**
     * v1 → v2：新增位置信息三列（摇一摇/NFC记账时自动定位用，见 Expense.java）。
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE expense_table ADD COLUMN latitude REAL");
            database.execSQL("ALTER TABLE expense_table ADD COLUMN longitude REAL");
            database.execSQL("ALTER TABLE expense_table ADD COLUMN locationName TEXT");
        }
    };

    /**
     * v2 → v3：新增省、市、区县、行政区划代码四列（消费足迹地图精准下钻与归类用）。
     */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE expense_table ADD COLUMN province TEXT");
            database.execSQL("ALTER TABLE expense_table ADD COLUMN city TEXT");
            database.execSQL("ALTER TABLE expense_table ADD COLUMN district TEXT");
            database.execSQL("ALTER TABLE expense_table ADD COLUMN adCode TEXT");
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
                    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build();
                }
            }
        }
        return INSTANCE;
    }
}
