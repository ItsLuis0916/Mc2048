package dev.luis.mc2048;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Prefs {

    public boolean sound;
    public int volume;
    public boolean confetti;
    public boolean glow;
    public boolean animations;
    public String theme;

    public Prefs(boolean sound, int volume, boolean confetti, boolean glow, boolean animations, String theme) {
        this.sound = sound;
        this.volume = Math.max(0, Math.min(100, volume));
        this.confetti = confetti;
        this.glow = glow;
        this.animations = animations;
        this.theme = theme;
    }

    public static Prefs read(ConfigurationSection section, Prefs defaults) {
        if (section == null) return defaults.copy();
        return new Prefs(section.getBoolean("sound", defaults.sound), section.getInt("volume", defaults.volume),
                section.getBoolean("confetti", defaults.confetti), section.getBoolean("glow", defaults.glow),
                section.getBoolean("animations", defaults.animations), section.getString("theme", defaults.theme));
    }

    public Prefs copy() {
        return new Prefs(sound, volume, confetti, glow, animations, theme);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sound", sound);
        map.put("volume", volume);
        map.put("confetti", confetti);
        map.put("glow", glow);
        map.put("animations", animations);
        map.put("theme", theme);
        return map;
    }
}
