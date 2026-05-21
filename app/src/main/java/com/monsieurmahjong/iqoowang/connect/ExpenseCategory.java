package com.monsieurmahjong.iqoowang.connect;

// 对应Kotlin data class ExpenseCategory
public class ExpenseCategory {
    private final int id;
    private final String name;
    private final int iconResId; // 本地drawable资源ID
    private final Long defaultAmount; // 可选默认金额（单位：分），可空

    // 全参构造函数
    public ExpenseCategory(int id, String name, int iconResId, Long defaultAmount) {
        this.id = id;
        this.name = name;
        this.iconResId = iconResId;
        this.defaultAmount = defaultAmount;
    }

    // 重载构造函数：实现Kotlin的默认参数 defaultAmount = null
    public ExpenseCategory(int id, String name, int iconResId) {
        this(id, name, iconResId, null);
    }

    // Getter方法（Kotlin data class自动生成，Java需显式声明）
    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getIconResId() {
        return iconResId;
    }

    public Long getDefaultAmount() {
        return defaultAmount;
    }

    // 可选：实现equals、hashCode和toString（与Kotlin data class行为完全一致）
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExpenseCategory that = (ExpenseCategory) o;
        return id == that.id &&
                iconResId == that.iconResId &&
                name.equals(that.name) &&
                (defaultAmount == null ? that.defaultAmount == null : defaultAmount.equals(that.defaultAmount));
    }

    @Override
    public int hashCode() {
        int result = id;
        result = 31 * result + name.hashCode();
        result = 31 * result + iconResId;
        result = 31 * result + (defaultAmount != null ? defaultAmount.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "ExpenseCategory{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", iconResId=" + iconResId +
                ", defaultAmount=" + defaultAmount +
                '}';
    }
}

// 对应Kotlin object CategoryProvider
