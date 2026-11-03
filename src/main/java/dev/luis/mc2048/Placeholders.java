package dev.luis.mc2048;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

final class Placeholders extends PlaceholderExpansion {

    private final Mc2048 plugin;

    Placeholders(Mc2048 plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "2048";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return null;
        Store.Record record = plugin.store().record(player.getUniqueId());
        return switch (params) {
            case "best" -> String.valueOf(record.best());
            case "score" -> String.valueOf(record.score());
            case "highest" -> String.valueOf(record.highest());
            default -> null;
        };
    }
}
