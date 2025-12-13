package ru.etu.timetable_bot.model;

import java.time.LocalDate;
import java.util.List;

public class DayTimetable {
    public LocalDate date;
    public List<Lessons> lessons;

    public boolean isEmpty() {
        return lessons == null || lessons.isEmpty();
    }
}