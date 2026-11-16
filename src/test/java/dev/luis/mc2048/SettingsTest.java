package dev.luis.mc2048;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingsTest {

    private static Settings settings() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                SettingsTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        return new Settings(config, new YamlConfiguration(), Map.of());
    }

    @Test
    void themesFallBackAndCycle() {
        Settings settings = settings();
        assertEquals("classic", settings.theme("missing").id());
        assertEquals(0x3b4252, settings.theme("dark").tile(2).background().asRGB());
        assertEquals(settings.theme("classic").tile(8192), settings.theme("classic").tile(4096));
        assertEquals("dark", settings.shiftTheme("classic", 1));
        assertEquals("candy", settings.shiftTheme("classic", -1));
    }

    @Test
    void themeRecoloursText() {
        Settings.Theme dark = settings().theme("dark");
        Component text = Component.text("a").color(TextColor.color(0x776e65)).append(Component.text("b").color(TextColor.color(0xeee4da)));
        Component recoloured = dark.recolor(text);
        assertEquals(TextColor.color(0xd8dee9), recoloured.color());
        assertEquals(TextColor.color(0xaab2c0), recoloured.children().getFirst().color());
    }
}
