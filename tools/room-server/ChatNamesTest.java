package com.campus.sport.lan;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** Chat history must follow a renamed profile. */
public final class ChatNamesTest {

    private static int failures = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            System.out.println("ok - " + message);
        } else {
            System.err.println("FAIL: " + message);
            failures += 1;
        }
    }

    public static void main(String[] args) throws Exception {
        ChatMessage oldMessage = new ChatMessage("m1", "旧名字", "p1", "之前发的", 1_000);
        ChatMessage newMessage = new ChatMessage("m2", "新名字", "p1", "刚刚发的", 2_000);

        Map<String, String> names = ChatNames.resolve(
                List.of(oldMessage, newMessage), List.of(), "me", "我");
        check("新名字".equals(names.get("p1")), "chat history uses the newest name from the profile");

        Map<String, String> local = ChatNames.resolve(
                List.of(oldMessage), List.of(), "p1", "本机新名字");
        check("本机新名字".equals(local.get("p1")), "the local profile name overrides stored history");

        ChatMessage rewritten = new ChatMessage("m1", "改名后", "p1", "之前发的", 1_000, 3_000);
        // Deliberately newer than nameUpdatedAt: a skewed device clock must not
        // resurrect the old name after the room rewrote the history.
        WorkoutEvent oldEvent = new WorkoutEvent(
                "e1", "p1", "d1", "旧名字", "squat", 10, 60, 0.9, 100, 9_000,
                "model-v1", false);
        Map<String, String> afterRename = ChatNames.resolve(
                List.of(rewritten), List.of(oldEvent), "me", "我");
        check("改名后".equals(afterRename.get("p1")),
                "a room-wide rename beats events recorded under the old name");

        File dir = Files.createTempDirectory("cadence-chat-names-").toFile();
        File file = new File(dir, "store.json");
        FileRoomStore store = new FileRoomStore(file);
        store.addChat(oldMessage);
        store.addChat(new ChatMessage("m3", "另一个人", "p2", "你好", 3_000));

        check(store.renameProfile("p1", "改名后"), "a rename reports that history changed");
        check("改名后".equals(store.chat().get(0).username), "the stored message carries the new name");
        check("另一个人".equals(store.chat().get(1).username), "other profiles keep their names");

        FileRoomStore reloaded = new FileRoomStore(file);
        check("改名后".equals(reloaded.chat().get(0).username), "the rename is persisted");
        check(!reloaded.renameProfile("p1", "改名后"), "renaming to the same value is a no-op");

        // Best-effort cleanup; Windows may still hold the file briefly.
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.delete();

        if (failures > 0) {
            System.err.println("CHAT NAME TEST FAILED (" + failures + ")");
            System.exit(1);
        }
        System.out.println("PASS");
    }
}
