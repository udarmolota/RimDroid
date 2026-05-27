package com.rimdroid.game;

import com.rimdroid.AppStorage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class GameInstanceManager {

    private static GameInstanceManager singleton;
    private final List<GameInstance> instances = new ArrayList<>();

    private GameInstanceManager() {
        reload();
    }

    public static GameInstanceManager requireSingleton() {
        if (singleton == null) singleton = new GameInstanceManager();
        return singleton;
    }

    public void reload() {
        instances.clear();
        File instancesDir = AppStorage.requireSingleton().getInstancesDir();
        if (!instancesDir.exists()) return;
        File[] dirs = instancesDir.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File dir : dirs) {
            GameInstance gi = new GameInstance(dir.getName());
            if (gi.isInstalled()) instances.add(gi);
        }
    }

    public List<GameInstance> getInstances() { return instances; }

    public GameInstance getByName(String name) {
        for (GameInstance gi : instances) {
            if (gi.getName().equals(name)) return gi;
        }
        return null;
    }

    public boolean hasAny() { return !instances.isEmpty(); }
}
