package dev.betavk.agent;

/** A group of patches. Implementations name classes and methods with strings only; they must not touch Minecraft classes while registering. */
public interface PatchSet {
    void register(PatchRegistry registry);
}
