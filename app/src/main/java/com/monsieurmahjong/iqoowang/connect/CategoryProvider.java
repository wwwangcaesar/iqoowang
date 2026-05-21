package com.monsieurmahjong.iqoowang.connect;


import com.monsieurmahjong.iqoowang.R;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CategoryProvider {
    // 私有构造函数，防止实例化（对应Kotlin object特性）
    private CategoryProvider() {}

    // 基本分类列表：不可变列表，与Kotlin listOf()行为一致
    public static final List<ExpenseCategory> basicCategories = Collections.unmodifiableList(
            Arrays.asList(
                    new ExpenseCategory(1, "餐饮美食", R.mipmap.ic_food),
                    new ExpenseCategory(2, "交通出行", R.mipmap.ic_transport),
                    new ExpenseCategory(3, "日用百货", R.mipmap.ic_shopping),
                    new ExpenseCategory(4, "零食饮品", R.mipmap.ic_drinks),
                    new ExpenseCategory(5, "娱乐交际", R.mipmap.ic_entertainment),
                    new ExpenseCategory(6, "其他", R.mipmap.ic_other)
            )
    );
}
