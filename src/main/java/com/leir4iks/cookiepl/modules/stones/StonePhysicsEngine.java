package com.leir4iks.cookiepl.modules.stones;

import java.util.ArrayList;

public class StonePhysicsEngine implements Runnable {

    private final StoneManager stoneManager;

    public StonePhysicsEngine(StoneManager stoneManager) {
        this.stoneManager = stoneManager;
    }

    @Override
    public void run() {
        if (stoneManager.getActiveStones().isEmpty()) {
            return;
        }

        for (ActiveStone stone : new ArrayList<>(stoneManager.getActiveStones())) {
            if (stone.isValid()) {
                stone.tick();
            }
        }
    }
}