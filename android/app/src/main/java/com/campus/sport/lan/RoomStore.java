package com.campus.sport.lan;

import java.util.List;

/** Storage for one room. Implemented by FileRoomStore; SQLite can replace it. */
public interface RoomStore {

    List<WorkoutEvent> events();

    /** Returns true when at least one event was new. */
    boolean addEvents(List<WorkoutEvent> incoming);

    List<ChatMessage> chat();

    void addChat(ChatMessage message);
}
