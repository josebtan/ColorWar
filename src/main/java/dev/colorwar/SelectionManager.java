package dev.colorwar;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SelectionManager {
    private final Map<UUID, Selection> map = new HashMap<>();

    public Selection get(UUID id) { return map.get(id); }
    public Selection getOrCreate(UUID id) { return map.computeIfAbsent(id, k -> new Selection()); }
    public void remove(UUID id) { map.remove(id); }
}
