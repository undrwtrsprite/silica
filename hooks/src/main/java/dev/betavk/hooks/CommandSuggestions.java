package dev.betavk.hooks;

import net.minecraft.src.GuiChat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** Suggestions for the single-player slash commands, shown above the chat input. */
public final class CommandSuggestions {
    private static final int TAB = 15, UP = 200, DOWN = 208;
    private static final List<String> ROOT = List.of("/help", "/seed", "/fly ", "/time ", "/give ", "/weather ");
    private static final List<String> FLY = List.of("/fly on", "/fly off", "/fly speed ", "/fly 2", "/fly 5", "/fly 10");
    private static final List<String> FLY_SPEED = List.of("/fly speed 1", "/fly speed 2", "/fly speed 5", "/fly speed 10", "/fly speed 20");
    private static final List<String> WEATHER = Commands.WEATHER_TYPES.stream().map(type -> "/weather " + type).toList();
    private static final Map<GuiChat, Integer> selections = new WeakHashMap<>();
    private static final List<String> history = new ArrayList<>();
    private static final Map<GuiChat, Recall> recalls = new WeakHashMap<>();

    private record Recall(int index, String draft) {}

    private CommandSuggestions() {
    }

    static void remember(String command) {
        if (history.size() == 100) history.removeFirst();
        history.add(command);
    }

    static void clearHistory() {
        history.clear();
        recalls.clear();
    }

    private static List<String> matches(GuiChat chat) {
        if (!chat.message.startsWith("/") || !Commands.enabled(chat.mc)) return List.of();
        String input = chat.message.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        List<String> candidates;
        if (!input.contains(" ")) candidates = ROOT;
        else if (input.startsWith("/fly speed ")) candidates = FLY_SPEED;
        else if (input.startsWith("/fly ")) candidates = FLY;
        else if (input.startsWith("/time set ")) candidates = List.of("/time set day", "/time set night");
        else if (input.startsWith("/time ")) candidates = List.of("/time set ");
        else if (input.startsWith("/weather ")) candidates = WEATHER;
        else return List.of();
        return candidates.stream().filter(candidate -> candidate.startsWith(input) && !candidate.equals(input)).toList();
    }

    public static boolean key(GuiChat chat, char character, int key) {
        if (key != TAB && key != UP && key != DOWN) {
            selections.remove(chat);
            recalls.remove(chat);
            return false;
        }
        Recall recall = recalls.get(chat);
        if (recall != null) {
            if (key == TAB) return false;
            int index = Math.max(0, recall.index() + (key == UP ? -1 : 1));
            if (index == history.size()) {
                chat.message = recall.draft();
                recalls.remove(chat);
            } else {
                chat.message = history.get(index);
                recalls.put(chat, new Recall(index, recall.draft()));
            }
            return true;
        }
        List<String> matches = matches(chat);
        if (matches.isEmpty()) {
            if (key != UP || history.isEmpty() || !Commands.enabled(chat.mc)) return false;
            int index = history.size() - 1;
            recalls.put(chat, new Recall(index, chat.message));
            chat.message = history.get(index);
            return true;
        }
        int selected = selections.getOrDefault(chat, 0) % matches.size();
        if (key == TAB) {
            chat.message = matches.get(selected);
            selections.remove(chat);
            recalls.remove(chat);
        } else {
            selections.put(chat, Math.floorMod(selected + (key == DOWN ? 1 : -1), matches.size()));
        }
        return true;
    }

    public static void draw(GuiChat chat, int mouseX, int mouseY, float partialTicks) {
        if (recalls.containsKey(chat)) return;
        List<String> matches = matches(chat);
        if (matches.isEmpty()) return;
        int selected = selections.getOrDefault(chat, 0) % matches.size();
        int width = matches.stream().mapToInt(chat.fontRenderer::getStringWidth).max().orElse(0) + 8;
        int y = chat.height - 15 - matches.size() * 11;
        for (int i = 0; i < matches.size(); i++, y += 11) {
            chat.drawRect(2, y, 2 + width, y + 11, i == selected ? 0xE0404040 : 0xD0101010);
            chat.drawString(chat.fontRenderer, matches.get(i).stripTrailing(), 6, y + 1, i == selected ? 0xFFFF55 : 0xFFFFFF);
        }
    }
}
