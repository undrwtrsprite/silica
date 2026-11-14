package dev.betavk.agent;

import java.util.ArrayList;
import java.util.List;

/** A stand-in for a game class: the self test patches this one. */
public class Toy {
    public static final List<String> log = new ArrayList<>();

    public int add(int a, int b) {
        return a + b;
    }

    public void run(String s) {
        log.add("run:" + s);
    }

    public void caller() {
        helper();
        log.add("after-helper");
    }

    public void helper() {
        log.add("helper");
    }

    public int redirectedStatic() {
        return Math.max(3, 5);
    }

    public float scale = 2.0F;

    public float fov() {
        float v = 70.0F;
        return v + scale;
    }

    public int setter;

    public void assign() {
        this.setter = 5;
    }

    public String name(int i) {
        return null;
    }

    public String pick(int i) {
        return "orig" + i;
    }

    public static String shared = "plain";

    public String readShared() {
        return shared;
    }

    public void writeShared() {
        shared = "written";
    }

    public int twice() {
        return Math.max(1, 2) + Math.max(3, 4);
    }

    public int five() {
        int base = 5;
        return base + 2;
    }

    public String guarded(int i) {
        return "ran" + i;
    }

    public String redirectedInstance(String s) {
        return s.trim();
    }
}
