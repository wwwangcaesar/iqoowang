package com.monsieurmahjong.iqoowang.dao;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {Expense.class},
        version = 1,
        exportSchema = false // 严格对应原代码配置
)
public abstract class AppDatabase extends RoomDatabase {
    public abstract ExpenseDao expenseDao();

    // 对应Kotlin的@Volatile INSTANCE变量
    private static volatile AppDatabase INSTANCE;

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
                    ).build();
                }
            }
        }
        return INSTANCE;
    }
}
