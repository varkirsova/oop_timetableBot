package ru.etu.timetable_bot.utils;

import java.time.DayOfWeek;
import java.time.LocalDate;

public class DateUtils {

    public static boolean isEvenWeek(LocalDate date) {
        LocalDate start = LocalDate.of(2025, 9, 1);
        if (date.isBefore(start)) {
            start = LocalDate.of(2024, 9, 2);
        }

        LocalDate firstMonday = start.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long days = java.time.temporal.ChronoUnit.DAYS.between(firstMonday, date);
        int weekNumber = (int) (days / 7) + 1;
        return weekNumber % 2 == 0;
    }

    public static LocalDate findNextDay(DayOfWeek target, boolean evenWeek) {
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 14; i++) {
            LocalDate candidate = today.plusDays(i);
            if (candidate.getDayOfWeek() == target && isEvenWeek(candidate) == evenWeek) {
                return candidate;
            }
        }
        return today.with(java.time.temporal.TemporalAdjusters.nextOrSame(target));
    }
}