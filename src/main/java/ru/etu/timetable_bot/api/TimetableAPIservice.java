package ru.etu.timetable_bot.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import ru.etu.timetable_bot.model.DayTimetable;
import ru.etu.timetable_bot.model.Lessons;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@Service
public class TimetableAPIservice {

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<DayTimetable> getScheduleForGroup(String groupNumber) throws Exception {
        String url = String.format(
                "https://digital.etu.ru/api/mobile/schedule?groupNumber=%s&season=autumn&year=2025&joinWeeks=true&withURL=true",
                groupNumber
        );

        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Spring Boot Telegram Bot (ru.etu)")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Ошибка API: " + response.code());
            }
            String json = response.body().string();
            return parseSchedule(json, groupNumber);
        }
    }

    private List<DayTimetable> parseSchedule(String json, String groupNumber) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode groupNode = root.get(groupNumber);

        if (groupNode == null || !groupNode.has("days")) {
            return Collections.emptyList();
        }

        JsonNode days = groupNode.get("days");
        List<DayTimetable> result = new ArrayList<>();
        LocalDate monday = LocalDate.now()
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        for (int i = 0; i <= 5; i++) {
            String dayKey = String.valueOf(i);
            if (!days.has(dayKey)) continue;

            JsonNode day = days.get(dayKey);
            DayTimetable ds = new DayTimetable();
            ds.date = monday.plusDays(i);
            ds.lessons = new ArrayList<>();

            if (day.has("lessons")) {
                for (JsonNode lessonNode : day.get("lessons")) {
                    Lessons lesson = new Lessons();
                    lesson.subject = lessonNode.get("name").asText();
                    lesson.type = lessonNode.get("subjectType").asText();
                    lesson.startTime = LocalTime.parse(lessonNode.get("start_time").asText());
                    lesson.endTime = LocalTime.parse(lessonNode.get("end_time").asText());

                    String main = lessonNode.get("teacher").asText();
                    String second = lessonNode.path("second_teacher").asText();
                    lesson.teacher = second.isEmpty() ? main : main + ", " + second;

                    String form = lessonNode.get("form").asText();
                    if ("online".equals(form)) {
                        lesson.room = "дистанционно";
                    } else {
                        lesson.room = lessonNode.path("room").asText();
                        if (lesson.room.isEmpty()) lesson.room = "—";
                    }

                    lesson.week = lessonNode.get("week").asText();

                    ds.lessons.add(lesson);
                }
                ds.lessons.sort(Comparator.comparing(l -> l.startTime));
            }
            result.add(ds);
        }
        return result;
    }
}