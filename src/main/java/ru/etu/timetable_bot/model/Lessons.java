package ru.etu.timetable_bot.model;

import java.time.LocalTime;

public class Lessons {
    public String subject;
    public String type;
    public String teacher;
    public String room;
    public String week;
    public LocalTime startTime;
    public LocalTime endTime;

    @Override
    public String toString() {
        return String.format(
                "%s–%s %s (%s), %s, %s",
                startTime, endTime, subject, type, teacher, room
        );
    }
}