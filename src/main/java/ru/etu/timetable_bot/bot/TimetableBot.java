package ru.etu.timetable_bot.bot;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.etu.timetable_bot.api.TimetableAPIservice;

import ru.etu.timetable_bot.utils.DateUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import java.util.ArrayList;

import java.util.List;

@Component
public class TimetableBot extends TelegramLongPollingBot {

    private final Map<Long, String> userSelectedDay = new ConcurrentHashMap<>();
    private final Map<Long, String> userGroup = new ConcurrentHashMap<>();
    private final Map<Long, String> userMenuState = new ConcurrentHashMap<>();


    @Value("${token}")
    private String botToken;

    @Value("${telegram.bot-name}")
    private String botName;

    private final TimetableAPIservice timetableAPIservice;


    public TimetableBot(TimetableAPIservice timetableAPIservice) {
        this.timetableAPIservice = timetableAPIservice;
    }

    @Override
    public String getBotUsername() {
        return botName;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText().trim();
            long chatId = update.getMessage().getChatId();

            try {
                if (text.equals("/start")) {
                    String group = userGroup.get(chatId);
                    userGroup.remove(chatId);
                    userSelectedDay.remove(chatId);
                    userMenuState.remove(chatId);
                    if (group == null) {
                        sendMsg(chatId, "Привет! Укажите номер вашей группы (4 цифры, например: 4354):");
                    } else {
                        sendMsg(chatId, "Ваша группа: " + group + "\nВыберите действие:");
                        showMainMenu(chatId);
                    }
                } else if (text.matches("\\d{4}")) {
                    userGroup.put(chatId, text);
                    sendMsg(chatId, "Группа сохранена: " + text);
                    showMainMenu(chatId);
                } else if (text.equals("Сменить группу")) {
                    sendMsg(chatId, "Введите новый номер группы (4 цифры):");
                } else if (text.equals("Ближайшая пара")) {
                    handleNearLesson(chatId);
                } else if (text.equals("Завтра")) {
                    handleTomorrow(chatId);
                } else if (text.equals("Вся неделя")) {
                    showWeekSelectionForFullWeek(chatId);
                } else if (text.equals("Расписание по дням")) {
                    showDayMenu(chatId);
                } else if (List.of("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота").contains(text)) {
                    showWeekSelectionMenu(chatId, text);
                } else if (text.equals("Нечетная неделя")) {
                    handleWeekSelection(chatId, "odd");
                } else if (text.equals("Четная неделя")) {
                    handleWeekSelection(chatId, "even");
                } else if (text.equals("Обе недели")) {
                    handleWeekSelection(chatId, "both");
                } else if (text.equals("Назад")) {
                    String state = userMenuState.get(chatId);
                    if ("week_selection".equals(state) || "week_selection_for_full".equals(state)) {
                        showDayMenu(chatId);
                    } else if ("day_selection".equals(state)) {
                        showMainMenu(chatId);
                    } else {
                        showMainMenu(chatId);
                    }
                } else {
                    sendMsg(chatId, "Пожалуйста, используйте кнопки.");
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendMsg(chatId, "Ошибка: " + e.getMessage());
            }
        }
    }

    private void handleNearLesson(long chatId) throws Exception {
        String group = userGroup.get(chatId);
        if (group == null) {
            sendMsg(chatId, "Сначала укажите группу.");
            return;
        }

        JsonNode rawSchedule = timetableAPIservice.getRawSchedule(group);
        JsonNode groupNode = rawSchedule.get(group);
        if (groupNode == null || !groupNode.has("days")) {
            sendMsg(chatId, "Расписание не найдено.");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();

        List<LessonWithDateTime> allLessons = new ArrayList<>();

        for (int dayIndex = 0; dayIndex < 7; dayIndex++) {
            LocalDate date = today.plusDays(dayIndex);
            JsonNode dayNode = groupNode.path("days").path(String.valueOf(date.getDayOfWeek().getValue() % 7));
            if (!dayNode.has("lessons")) continue;

            for (JsonNode l : dayNode.get("lessons")) {
                String week = l.get("week").asText();
                boolean isEvenDate = DateUtils.isEvenWeek(date);
                boolean matchesWeek = "0".equals(week) ||
                        (("1".equals(week) || "3".equals(week)) && !isEvenDate) ||
                        (("2".equals(week) || "4".equals(week)) && isEvenDate);

                if (matchesWeek) {
                    LocalTime start = LocalTime.parse(l.get("start_time").asText());
                    LocalDateTime lessonTime = date.atTime(start);
                    if (lessonTime.isAfter(now)) {
                        allLessons.add(new LessonWithDateTime(l, lessonTime));
                    }
                }
            }
        }

        if (allLessons.isEmpty()) {
            sendMsg(chatId, "Ближайшие 2 недели — занятий нет.");
            return;
        }

        allLessons.sort(Comparator.comparing(l -> l.dateTime));

        LessonWithDateTime next = allLessons.get(0);

        StringBuilder sb = new StringBuilder();
        sb.append("📅 Ближайшая пара\n\n");
        sb.append(formatLessonWithTime(next.lessonNode, 1));
        sb.append("\nℹ️ Чтобы увидеть расписание на день, используйте «Расписание по дням».");

        sendMsg(chatId, sb.toString());
    }

    private void handleTomorrow(long chatId) throws Exception {
        String group = userGroup.get(chatId);
        if (group == null) {
            sendMsg(chatId, "Сначала укажите группу.");
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.getDayOfWeek() == DayOfWeek.SUNDAY
                ? today.plusDays(1)
                : today.plusDays(1);

        boolean isEvenWeek = DateUtils.isEvenWeek(tomorrow);
        String weekStr = isEvenWeek ? "четной" : "нечетной";

        JsonNode rawSchedule = timetableAPIservice.getRawSchedule(group);
        JsonNode groupNode = rawSchedule.get(group);
        if (groupNode == null || !groupNode.has("days")) {
            sendMsg(chatId, "Расписание не найдено.");
            return;
        }

        int dayIndex = tomorrow.getDayOfWeek().getValue() % 7;
        JsonNode dayNode = groupNode.path("days").path(String.valueOf(dayIndex));
        if (!dayNode.has("lessons")) {
            sendMsg(chatId, "Завтра занятий нет.");
            return;
        }

        List<JsonNode> lessons = new ArrayList<>();
        for (JsonNode l : dayNode.get("lessons")) {
            String w = l.get("week").asText();
            if ("0".equals(w) ||
                    ("1".equals(w) || "3".equals(w) && !isEvenWeek) ||
                    ("2".equals(w) || "4".equals(w) && isEvenWeek)) {
                lessons.add(l);
            }
        }

        if (lessons.isEmpty()) {
            sendMsg(chatId, "Завтра занятий нет.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📅 Завтра\n\n");
        int index = 1;
        for (JsonNode l : lessons) {
            sb.append(formatLessonWithTime(l, index)).append("\n");
            index++;
        }
        sb.append("\nℹ️ Чтобы увидеть расписание на другой день, используйте «Расписание по дням».");

        sendMsg(chatId, sb.toString());
    }

    private static class LessonWithDateTime {
        JsonNode lessonNode;
        LocalDateTime dateTime;

        LessonWithDateTime(JsonNode lessonNode, LocalDateTime dateTime) {
            this.lessonNode = lessonNode;
            this.dateTime = dateTime;
        }
    }

    private void handleWeekSelection(long chatId, String weekType) throws Exception {
        String state = userMenuState.get(chatId);
        if ("week_selection_for_full".equals(state)) {
            handleFullWeekForType(chatId, weekType);
        } else {
            // Режим: день недели
            String group = userGroup.get(chatId);
            String dayName = userSelectedDay.get(chatId);
            if (group == null || dayName == null) {
                sendMsg(chatId, "Сначала выберите группу и день.");
                return;
            }
            handleDayForWeek(chatId, weekType);
        }
    }


    private void showWeekSelectionForFullWeek(long chatId) {
        userMenuState.put(chatId, "week_selection_for_full");
        boolean isEven = DateUtils.isEvenWeek(LocalDate.now());
        String currentWeek = isEven ? "четная" : "нечетная";


        String message = String.format(
                "📅 Вся неделя\nСейчас идёт %s неделя.\nКакую неделю показать?",
                currentWeek
        );

        SendMessage msg = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(message)
                .replyMarkup(createWeekSelectionMenu())
                .build();

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void showWeekSelectionMenu(long chatId, String dayName) {
        userSelectedDay.put(chatId, dayName);
        userMenuState.put(chatId, "week_selection");
        boolean isEven = DateUtils.isEvenWeek(LocalDate.now());
        String currentWeek = isEven ? "четная" : "нечетная";

        String message = String.format(
                "📅 %s\nСейчас идёт %s неделя.\nКакую неделю показать?",
                dayName, currentWeek
        );

        SendMessage msg = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(message)
                .replyMarkup(createWeekSelectionMenu())
                .build();

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private ReplyKeyboardMarkup createWeekSelectionMenu() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow r1 = new KeyboardRow();
        r1.add("Нечетная неделя");
        r1.add("Четная неделя");
        rows.add(r1);

        KeyboardRow r2 = new KeyboardRow();
        r2.add("Обе недели");
        r2.add("Назад");
        rows.add(r2);

        keyboard.setKeyboard(rows);
        keyboard.setResizeKeyboard(true);
        return keyboard;
    }

    private void handleDayForWeek(long chatId, String weekType) throws Exception {
        String group = userGroup.get(chatId);
        String dayName = userSelectedDay.get(chatId);

        if (group == null || dayName == null) {
            sendMsg(chatId, "Ошибка. Начните заново.");
            return;
        }

        JsonNode rawSchedule = timetableAPIservice.getRawSchedule(group);
        JsonNode groupNode = rawSchedule.get(group);
        if (groupNode == null || !groupNode.has("days")) {
            sendMsg(chatId, "Расписание не найдено.");
            return;
        }

        Integer dayIndex = getDayIndex(dayName);
        if (dayIndex == null) {
            sendMsg(chatId, "Неизвестный день.");
            return;
        }

        JsonNode days = groupNode.get("days");
        JsonNode dayNode = days.get(dayIndex.toString());
        if (dayNode == null || !dayNode.has("lessons")) {
            sendMsg(chatId, "В " + dayName.toLowerCase() + " занятий нет.");
            return;
        }

        List<JsonNode> lessons = new ArrayList<>();
        for (JsonNode l : dayNode.get("lessons")) {
            String w = l.get("week").asText();
            if ("both".equals(weekType)) {
                lessons.add(l);
            } else if ("odd".equals(weekType) && ("1".equals(w) || "3".equals(w) || "0".equals(w))) {
                lessons.add(l);
            } else if ("even".equals(weekType) && ("2".equals(w) || "4".equals(w) || "0".equals(w))) {
                lessons.add(l);
            }
        }

        if (lessons.isEmpty()) {
            String weekStr = "odd".equals(weekType) ? "нечетной" : "четной";
            sendMsg(chatId, "В " + dayName.toLowerCase() + " на " + weekStr + " неделе занятий нет.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        if ("both".equals(weekType)) {
            sb.append("📅 ").append(dayName).append("\n\n");
            Map<String, List<JsonNode>> slots = new LinkedHashMap<>();
            for (JsonNode l : lessons) {
                String key = l.get("start_time").asText() + "-" + l.get("end_time").asText();
                slots.computeIfAbsent(key, k -> new ArrayList<>()).add(l);
            }

            for (List<JsonNode> slot : slots.values()) {
                if (slot.size() == 1) {
                    JsonNode l = slot.get(0);
                    if ("0".equals(l.get("week").asText())) {
                        sb.append("• ").append(formatLessonWithTime(l, 0)).append("\n");
                    } else {
                        String wType = ("1".equals(l.get("week").asText()) || "3".equals(l.get("week").asText())) ? "Нечетная" : "Четная";
                        sb.append("• ").append(wType).append(": ").append(formatLessonWithTime(l, 0)).append("\n");
                    }
                } else {
                    for (JsonNode l : slot) {
                        String wType = ("1".equals(l.get("week").asText()) || "3".equals(l.get("week").asText())) ? "Нечетная" : "Четная";
                        sb.append("• ").append(wType).append(": ").append(formatLessonWithTime(l, 0)).append("\n");
                    }
                }
            }
        } else {
            String weekTitle = "odd".equals(weekType) ? "нечетная" : "четная";
            sb.append("📅 ").append(dayName).append("\n(неделя: ").append(weekTitle).append(")\n\n");
            int index = 1;
            for (JsonNode l : lessons) {
                sb.append(formatLessonWithTime(l, index)).append("\n");
                index++;
            }
        }

        sendMsg(chatId, sb.toString());
    }

    private void handleFullWeekForType(long chatId, String weekType) throws Exception {
        String group = userGroup.get(chatId);
        if (group == null) {
            sendMsg(chatId, "Сначала укажите группу.");
            return;
        }

        JsonNode rawSchedule = timetableAPIservice.getRawSchedule(group);
        JsonNode groupNode = rawSchedule.get(group);
        if (groupNode == null || !groupNode.has("days")) {
            sendMsg(chatId, "Расписание не найдено.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        boolean isEven = "even".equals(weekType);
        boolean isBoth = "both".equals(weekType);


        if (isBoth) {
            sb.append("📅 Расписание на обе недели\n\n");
            sb.append(" Нечетная неделя\n");
            appendWeek(sb, groupNode, false); // false - нечетная
            sb.append("\n Четная неделя\n");
            appendWeek(sb, groupNode, true);  // true - четная
        } else {
            String title = isEven ? "четной" : "нечетной";
            sb.append("📅Расписание на ").append(title).append(" неделе.\n\n");
            appendWeek(sb, groupNode, isEven);
        }

        sendMsg(chatId, sb.toString());
    }

    private void appendWeek(StringBuilder sb, JsonNode groupNode, boolean evenWeek) {
        String[] dayNames = {"Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота"};

        for (int i = 0; i < 6; i++) {
            JsonNode dayNode = groupNode.path("days").path(String.valueOf(i));
            if (!dayNode.has("lessons")) continue;

            List<JsonNode> filtered = new ArrayList<>();
            for (JsonNode lesson : dayNode.get("lessons")) {
                String w = lesson.get("week").asText();
                if ("0".equals(w)) {
                    filtered.add(lesson);
                } else {
                    boolean lessonEven = "2".equals(w) || "4".equals(w);
                    if (lessonEven == evenWeek) {
                        filtered.add(lesson);
                    }
                }
            }

            if (!filtered.isEmpty()) {
                sb.append("").append(dayNames[i]).append("\n");
                int index = 1;
                for (JsonNode l : filtered) {
                    sb.append(formatLessonWithTime(l, index)).append("\n");
                    index++;
                }
                sb.append("\n");
            }
        }

        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
    }


    private String formatLessonWithTime(JsonNode l, int index) {
        String start = l.get("start_time").asText();
        String end = l.get("end_time").asText();
        String subject = l.get("name").asText();
        String type = l.get("subjectType").asText();
        String teacher = getTeacher(l);
        String room = getRoom(l);

        StringBuilder sb = new StringBuilder();

        sb.append(index).append(". ").append(subject).append(" (").append(type).append(")\n");

        sb.append("🕒 ").append(start).append(" - ").append(end).append("\n");

        if (!teacher.isEmpty()) {
            sb.append("Преподаватель: ").append(teacher).append("\n");
        }

        if ("онлайн".equalsIgnoreCase(room)) {
            sb.append("Форма: дистанционно\n");
        } else if (!room.isEmpty() && !room.equals("—")) {
            sb.append("Ауд. ").append(room).append("\n");
        }

        JsonNode urlNode = l.path("url");
        String url = null;
        if (!urlNode.isMissingNode() && !urlNode.isNull() && urlNode.asText() != null) {
            url = urlNode.asText().trim();
        }
        if (url != null && !url.isEmpty() && !url.equals("null") && !url.equals("—")) {
            sb.append("Сслылка: ").append(url).append("\n");
        }

        return sb.toString();
    }


    private String getTeacher(JsonNode l) {
        String main = l.path("teacher").asText("").trim();
        String second = l.path("second_teacher").asText("").trim();

        if (main.isEmpty() && second.isEmpty()) {
            return "";
        }
        if (second.isEmpty()) {
            return main;
        }
        return main + ", " + second;
    }

    private String getRoom(JsonNode l) {
        String form = l.path("form").asText("");
        if ("online".equalsIgnoreCase(form) || "онлайн".equalsIgnoreCase(form)) {
            return "онлайн";
        }
        String room = l.path("room").asText("");
        return room.isEmpty() ? "—" : room;
    }

    private Integer getDayIndex(String dayName) {
        return switch (dayName) {
            case "Понедельник" -> 0;
            case "Вторник" -> 1;
            case "Среда" -> 2;
            case "Четверг" -> 3;
            case "Пятница" -> 4;
            case "Суббота" -> 5;
            default -> null;
        };
    }


    private void sendMsg(long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .build();
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void showMainMenu(long chatId) {
        userMenuState.put(chatId, "main");
        SendMessage msg = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text("Выберите действие:")
                .replyMarkup(createMainMenu())
                .build();
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private ReplyKeyboardMarkup createMainMenu() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("Ближайшая пара");
        row1.add("Завтра");
        rows.add(row1);

        KeyboardRow row2 = new KeyboardRow();
        row2.add("Расписание по дням");
        row2.add("Вся неделя");
        rows.add(row2);

        KeyboardRow row3 = new KeyboardRow();
        row3.add("Сменить группу");
        rows.add(row3);

        keyboard.setKeyboard(rows);
        keyboard.setResizeKeyboard(true);
        return keyboard;
    }

    private void showDayMenu(long chatId) {
        userMenuState.put(chatId, "day_selection");
        SendMessage msg = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text("Выберите день:")
                .replyMarkup(createDayMenu())
                .build();
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private ReplyKeyboardMarkup createDayMenu() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow r1 = new KeyboardRow();
        r1.add("Понедельник");
        r1.add("Вторник");
        r1.add("Среда");
        rows.add(r1);

        KeyboardRow r2 = new KeyboardRow();
        r2.add("Четверг");
        r2.add("Пятница");
        r2.add("Суббота");
        rows.add(r2);

        KeyboardRow r3 = new KeyboardRow();
        r3.add("Назад");
        rows.add(r3);

        keyboard.setKeyboard(rows);
        keyboard.setResizeKeyboard(true);
        return keyboard;
    }
}