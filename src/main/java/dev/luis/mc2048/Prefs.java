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

    public Prefs(boolean sound, int volume, boolean confetti, boolean glow, boolean animations) {
        this.sound = sound;
        this.volume = Math.max(0, Math.min(100, volume));
        this.confetti = confetti;
        this.glow = glow;
        this.animations = animations;
    }

    public static Prefs read(ConfigurationSection section, Prefs defaults) {
        if (section == null) return defaults.copy();
        return new Prefs(section.getBoolean("sound", defaults.sound), section.getInt("volume", defaults.volume),
                section.getBoolean("confetti", defaults.confetti), section.getBoolean("glow", defaults.glow),
                section.getBoolean("animations", defaults.animations));
    }

    public Prefs copy() {
        return new Prefs(sound, volume, confetti, glow, animations);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sound", sound);
        map.put("volume", volume);
        map.put("confetti", confetti);
        map.put("glow", glow);
        map.put("animations", animations);
        return map;
    }
}
