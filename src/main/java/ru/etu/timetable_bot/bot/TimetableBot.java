package ru.etu.timetable_bot.bot;

import java.time.format.DateTimeFormatter;
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
                    if ("week_selection_for_full".equals(state)) {
                        showMainMenu(chatId);
                    } else if ("week_selection".equals(state)) {
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
        else if (update.hasMessage()) {
            long chatId = update.getMessage().getChatId();
            sendMsg(chatId, "Я понимаю только текстовые сообщения.\n" +
                    "Пожалуйста, используйте кнопки меню или введите команду /start");
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


    private void handleDayForWeek(long chatId, String weekType) throws Exception {
        String group = userGroup.get(chatId);
        String dayName = userSelectedDay.get(chatId);
        if (group == null || dayName == null) {
            sendMsg(chatId, "Сначала выберите группу и день.");
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

        JsonNode dayNode = groupNode.path("days").path(String.valueOf(dayIndex));
        if (!dayNode.has("lessons") || dayNode.get("lessons").size() == 0) {
            String weekStr = "odd".equals(weekType) ? "нечетной" : "четной";
            sendMsg(chatId, "В " + dayName.toLowerCase() + " на " + weekStr + " неделе занятий нет.");
            return;
        }

        Map<String, List<JsonNode>> slots = new LinkedHashMap<>();
        for (JsonNode l : dayNode.get("lessons")) {
            String key = l.get("start_time").asText() + "-" + l.get("end_time").asText();
            slots.computeIfAbsent(key, k -> new ArrayList<>()).add(l);
        }

        List<JsonNode> resultLessons = new ArrayList<>();
        boolean isEvenRequest = "even".equals(weekType);

        for (List<JsonNode> slot : slots.values()) {
            JsonNode chosen = null;

            if (isEvenRequest) {
                for (JsonNode l : slot) {
                    String w = l.get("week").asText();
                    if ("2".equals(w)) {
                        chosen = l;
                        break;
                    }
                }
                if (chosen == null) {
                    for (JsonNode l : slot) {
                        String w = l.get("week").asText();
                        if ("1".equals(w) || "3".equals(w)) {
                            chosen = l;
                            break;
                        }
                    }
                }
            } else {
                for (JsonNode l : slot) {
                    String w = l.get("week").asText();
                    if ("1".equals(w) || "3".equals(w)) {
                        chosen = l;
                        break;
                    }
                }
                if (chosen == null) {
                    for (JsonNode l : slot) {
                        String w = l.get("week").asText();
                        if ("2".equals(w)) {
                            chosen = l;
                            break;
                        }
                    }
                }
            }

            if (chosen != null) {
                resultLessons.add(chosen);
            }
        }

        if (resultLessons.isEmpty()) {
            String weekStr = isEvenRequest ? "четной" : "нечетной";
            sendMsg(chatId, "В " + dayName.toLowerCase() + " на " + weekStr + " неделе занятий нет.");
            return;
        }

        resultLessons.sort(Comparator.comparing(l -> l.get("start_time").asText()));

        StringBuilder sb = new StringBuilder();
        String weekTitle = isEvenRequest ? "четная" : "нечетная";
        sb.append("📅 ").append(dayName).append("\n(неделя: ").append(weekTitle).append(")\n\n");
        int index = 1;
        for (JsonNode l : resultLessons) {
            sb.append(formatLessonWithTime(l, index)).append("\n");
            index++;
        }

        sendMsg(chatId, sb.toString());
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

        for (int offset = 0; offset < 14; offset++) {
            LocalDate date = now.toLocalDate().plusDays(offset);
            int dayIndex = date.getDayOfWeek().getValue() - 1;
            if (dayIndex >= 6) continue;

            JsonNode dayNode = groupNode.path("days").path(String.valueOf(dayIndex));
            if (!dayNode.has("lessons")) continue;

            Map<String, List<JsonNode>> slots = new LinkedHashMap<>();
            for (JsonNode l : dayNode.get("lessons")) {
                String key = l.get("start_time").asText() + "-" + l.get("end_time").asText();
                slots.computeIfAbsent(key, k -> new ArrayList<>()).add(l);
            }

            List<Map.Entry<String, List<JsonNode>>> sortedSlots = new ArrayList<>(slots.entrySet());
            sortedSlots.sort(Map.Entry.comparingByKey());

            for (Map.Entry<String, List<JsonNode>> entry : sortedSlots) {
                LocalTime start = LocalTime.parse(entry.getKey().split("-")[0]);
                LocalDateTime lessonTime = date.atTime(start);
                if (lessonTime.isBefore(now)) {
                    continue;
                }

                JsonNode chosen = null;
                boolean isEvenDate = DateUtils.isEvenWeek(date);

                if (isEvenDate) {
                    for (JsonNode l : entry.getValue()) {
                        String w = l.get("week").asText();
                        if ("2".equals(w)) {
                            chosen = l;
                            break;
                        }
                    }
                    if (chosen == null) {
                        for (JsonNode l : entry.getValue()) {
                            String w = l.get("week").asText();
                            if ("1".equals(w) || "3".equals(w)) {
                                chosen = l;
                                break;
                            }
                        }
                    }
                } else {
                    for (JsonNode l : entry.getValue()) {
                        String w = l.get("week").asText();
                        if ("1".equals(w) || "3".equals(w)) {
                            chosen = l;
                            break;
                        }
                    }
                    if (chosen == null) {
                        for (JsonNode l : entry.getValue()) {
                            String w = l.get("week").asText();
                            if ("2".equals(w)) {
                                chosen = l;
                                break;
                            }
                        }
                    }
                }

                if (chosen != null) {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
                    String formattedDate = date.format(formatter);


                    StringBuilder sb = new StringBuilder();
                    sb.append("📅 Ближайшая парa (").append(formattedDate).append(") \n\n");
                    sb.append(formatLessonWithTime(chosen, 1));

                    sendMsg(chatId, sb.toString());
                    return;
                }
            }
        }

        sendMsg(chatId, "Ближайшие 2 недели — занятий нет.");
    }

    private void handleTomorrow(long chatId) throws Exception {
        String group = userGroup.get(chatId);
        if (group == null) {
            sendMsg(chatId, "Сначала укажите группу.");
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);

        DayOfWeek tomorrowDayOfWeek = tomorrow.getDayOfWeek();

        if (tomorrowDayOfWeek == DayOfWeek.SUNDAY) {
            sendMsg(chatId, "Завтра воскресенье - занятий нет.");
            return;
        }

        int dayIndex = tomorrowDayOfWeek.getValue() - 1; // Monday=0, Tuesday=1, ..Saturday=5

        boolean isEvenWeek = DateUtils.isEvenWeek(tomorrow);

        JsonNode rawSchedule = timetableAPIservice.getRawSchedule(group);
        JsonNode groupNode = rawSchedule.get(group);
        if (groupNode == null || !groupNode.has("days")) {
            sendMsg(chatId, "Расписание не найдено.");
            return;
        }

        JsonNode dayNode = groupNode.path("days").path(String.valueOf(dayIndex));
        if (!dayNode.has("lessons")) {
            sendMsg(chatId, "Завтра занятий нет.");
            return;
        }

        Map<String, List<JsonNode>> slots = new LinkedHashMap<>();
        for (JsonNode l : dayNode.get("lessons")) {
            String key = l.get("start_time").asText() + "-" + l.get("end_time").asText();
            slots.computeIfAbsent(key, k -> new ArrayList<>()).add(l);
        }

        List<JsonNode> resultLessons = new ArrayList<>();

        for (List<JsonNode> slot : slots.values()) {
            JsonNode chosen = null;

            if (isEvenWeek) {
                for (JsonNode l : slot) {
                    String w = l.get("week").asText();
                    if ("2".equals(w)) {
                        chosen = l;
                        break;
                    }
                }
                if (chosen == null) {
                    for (JsonNode l : slot) {
                        String w = l.get("week").asText();
                        if ("1".equals(w) || "3".equals(w)) {
                            chosen = l;
                            break;
                        }
                    }
                }
            } else {
                for (JsonNode l : slot) {
                    String w = l.get("week").asText();
                    if ("1".equals(w) || "3".equals(w)) {
                        chosen = l;
                        break;
                    }
                }
                if (chosen == null) {
                    for (JsonNode l : slot) {
                        String w = l.get("week").asText();
                        if ("2".equals(w)) {
                            chosen = l;
                            break;
                        }
                    }
                }
            }

            if (chosen != null) {
                resultLessons.add(chosen);
            }
        }

        if (resultLessons.isEmpty()) {
            sendMsg(chatId, "Завтра занятий нет.");
            return;
        }

        String dayName = switch (tomorrowDayOfWeek) {
            case MONDAY -> "Понедельник";
            case TUESDAY -> "Вторник";
            case WEDNESDAY -> "Среда";
            case THURSDAY -> "Четверг";
            case FRIDAY -> "Пятница";
            case SATURDAY -> "Суббота";
            default -> "День";
        };

        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        String formattedDate = tomorrow.format(dateFormatter);

        StringBuilder sb = new StringBuilder();
        sb.append("📅 Завтра - ").append(dayName)
                .append(" (").append(formattedDate).append(")\n\n");

        int index = 1;
        for (JsonNode l : resultLessons) {
            sb.append(formatLessonWithTime(l, index)).append("\n");
            index++;
        }

        sendMsg(chatId, sb.toString());
    }

    private void appendWeek(StringBuilder sb, JsonNode groupNode, boolean evenWeek) {
        String[] dayNames = {"Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота"};

        for (int i = 0; i < 6; i++) {
            JsonNode dayNode = groupNode.path("days").path(String.valueOf(i));
            if (!dayNode.has("lessons")) continue;

            Map<String, List<JsonNode>> slots = new LinkedHashMap<>();
            for (JsonNode l : dayNode.get("lessons")) {
                String timeKey = l.get("start_time").asText() + "-" + l.get("end_time").asText();
                slots.computeIfAbsent(timeKey, k -> new ArrayList<>()).add(l);
            }

            List<JsonNode> resultLessons = new ArrayList<>();
            for (List<JsonNode> slot : slots.values()) {
                JsonNode chosen = null;

                if (evenWeek) {
                    for (JsonNode l : slot) {
                        String w = l.get("week").asText();
                        if ("2".equals(w)) {
                            chosen = l;
                            break;
                        }
                    }
                    if (chosen == null) {
                        for (JsonNode l : slot) {
                            String w = l.get("week").asText();
                            if ("1".equals(w) || "3".equals(w)) {
                                chosen = l;
                                break;
                            }
                        }
                    }
                } else {
                    for (JsonNode l : slot) {
                        String w = l.get("week").asText();
                        if ("1".equals(w) || "3".equals(w)) {
                            chosen = l;
                            break;
                        }
                    }
                    if (chosen == null) {
                        for (JsonNode l : slot) {
                            String w = l.get("week").asText();
                            if ("2".equals(w)) {
                                chosen = l;
                                break;
                            }
                        }
                    }
                }

                if (chosen != null) {
                    resultLessons.add(chosen);
                }
            }

            if (!resultLessons.isEmpty()) {
                resultLessons.sort(Comparator.comparing(l -> l.get("start_time").asText()));

                sb.append("\uD83D\uDD37").append(" ").append(dayNames[i]).append("\n");
                int index = 1;
                for (JsonNode l : resultLessons) {
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


    private void handleWeekSelection(long chatId, String weekType) throws Exception {
        String state = userMenuState.get(chatId);
        if ("week_selection_for_full".equals(state)) {
            handleFullWeekForType(chatId, weekType);
        } else {
            if ("both".equals(weekType)) {
                sendMsg(chatId, "Для дня недели выберите нечетную или четную неделю.");
                return;
            }
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
                .replyMarkup(createWeekSelectionMenu(true))
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
                .replyMarkup(createWeekSelectionMenu(false))
                .build();

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private ReplyKeyboardMarkup createWeekSelectionMenu(boolean forFullWeek) {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow r1 = new KeyboardRow();
        r1.add("Нечетная неделя");
        r1.add("Четная неделя");
        rows.add(r1);

        KeyboardRow r2 = new KeyboardRow();
        if (forFullWeek) {
            r2.add("Обе недели");
        }
        r2.add("Назад");
        rows.add(r2);

        keyboard.setKeyboard(rows);
        keyboard.setResizeKeyboard(true);
        return keyboard;
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
        if ("both".equals(weekType)) {
            sb.append("📅 Расписание на обе недели:\n\n");
            appendCombinedWeek(sb, groupNode);
        } else {
            boolean isEven = "even".equals(weekType);
            String title = isEven ? "четную" : "нечетную";
            sb.append("📅 Расписание на ").append(title).append(" неделю:\n\n");
            appendWeek(sb, groupNode, isEven);
        }
        sendMsg(chatId, sb.toString());
    }

    private void appendCombinedWeek(StringBuilder sb, JsonNode groupNode) {
        String[] dayNames = {"Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота"};

        for (int i = 0; i < 6; i++) {
            JsonNode dayNode = groupNode.path("days").path(String.valueOf(i));
            if (!dayNode.has("lessons")) continue;

            Map<String, List<JsonNode>> slots = new LinkedHashMap<>();
            for (JsonNode l : dayNode.get("lessons")) {
                String key = l.get("start_time").asText() + "-" + l.get("end_time").asText();
                slots.computeIfAbsent(key, k -> new ArrayList<>()).add(l);
            }

            if (slots.isEmpty()) continue;

            sb.append("\uD83D\uDD37").append(" ").append(dayNames[i]).append("\n");
            int index = 1;
            for (List<JsonNode> slot : slots.values()) {
                if (slot.size() == 1) {
                    sb.append(formatLessonWithTime(slot.get(0), index));
                } else {
                    String start = slot.get(0).get("start_time").asText();
                    String end = slot.get(0).get("end_time").asText();

                    JsonNode oddLesson = null, evenLesson = null;
                    for (JsonNode l : slot) {
                        String w = l.get("week").asText();
                        if ("1".equals(w) || "3".equals(w)) {
                            oddLesson = l;
                        } else if ("2".equals(w) || "4".equals(w)) {
                            evenLesson = l;
                        }
                    }

                    sb.append(index).append(". ");
                    if (oddLesson != null && evenLesson != null) {
                        sb.append(oddLesson.get("name").asText()).append(" (").append(oddLesson.get("subjectType").asText()).append(") (нечетная) / ")
                                .append(evenLesson.get("name").asText()).append(" (").append(evenLesson.get("subjectType").asText()).append(") (четная)\n");
                    } else if (oddLesson != null) {
                        sb.append(oddLesson.get("name").asText()).append(" (").append(oddLesson.get("subjectType").asText()).append(") (нечетная)\n");
                    } else if (evenLesson != null) {
                        sb.append(evenLesson.get("name").asText()).append(" (").append(evenLesson.get("subjectType").asText()).append(") (четная)\n");
                    }

                    sb.append("🕒 ").append(start).append(" - ").append(end).append("\n");

                    String teacherOdd = oddLesson != null ? getTeacher(oddLesson) : null;
                    String teacherEven = evenLesson != null ? getTeacher(evenLesson) : null;
                    if (teacherOdd != null && teacherEven != null && !teacherOdd.equals(teacherEven)) {
                        sb.append("Преподаватель: ").append(teacherOdd).append(" (нечетная) / ").append(teacherEven).append(" (четная)\n");
                    } else if (teacherOdd != null) {
                        sb.append("Преподаватель: ").append(teacherOdd).append("\n");
                    } else if (teacherEven != null) {
                        sb.append("Преподаватель: ").append(teacherEven).append("\n");
                    }

                    String roomOdd = oddLesson != null ? getRoom(oddLesson) : null;
                    String roomEven = evenLesson != null ? getRoom(evenLesson) : null;
                    if (roomOdd != null && roomEven != null && !roomOdd.equals(roomEven)) {
                        sb.append("Ауд. ").append(roomOdd).append(" (нечетная) / Ауд. ").append(roomEven).append(" (четная)\n");
                    } else if (roomOdd != null && !"—".equals(roomOdd)) {
                        sb.append("Ауд. ").append(roomOdd).append("\n");
                    } else if (roomEven != null && !"—".equals(roomEven)) {
                        sb.append("Ауд. ").append(roomEven).append("\n");
                    } else if ("онлайн".equals(roomOdd) || "онлайн".equals(roomEven)) {
                        sb.append("Форма: дистанционно\n");
                    }
                }
                index++;
                sb.append("\n");
            }
            sb.append("\n");
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