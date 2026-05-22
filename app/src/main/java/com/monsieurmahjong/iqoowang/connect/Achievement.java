package com.monsieurmahjong.iqoowang.connect;

// Achievement.java

public class Achievement {
    private final int id;
    private final String name;
    private final String description;
    private final int icon; // Material Icons名称
    private final boolean isUnlocked;

    public Achievement(int id, String name, String description, int icon, boolean isUnlocked) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.icon = icon;
        this.isUnlocked = isUnlocked;
    }

    // Getter方法
    public int getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getIcon() { return icon; }
    public boolean isUnlocked() { return isUnlocked; }
}
