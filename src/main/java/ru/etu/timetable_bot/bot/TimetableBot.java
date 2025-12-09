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
import ru.etu.timetable_bot.model.DayTimetable;
import ru.etu.timetable_bot.model.Lessons;
import ru.etu.timetable_bot.utils.DateUtils;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import java.util.ArrayList;

import java.util.List;
import java.util.StringJoiner;

@Component
public class TimetableBot extends TelegramLongPollingBot {

    private final Map<Long, String> userAction = new ConcurrentHashMap<>();
    private final Map<Long, String> userSelectedDay = new ConcurrentHashMap<>();

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

    private void askForGroup(long chatId, String action) {
        userAction.put(chatId, action);
        sendMsg(chatId, "Введите номер группы (4 цифры, например: 4354):");
    }

    private void askForGroupForDay(long chatId, String day) {
        userAction.put(chatId, "day");
        userSelectedDay.put(chatId, day);
        sendMsg(chatId, "Введите номер группы (4 цифры, например: 4354):");
    }

    private void handleGroupInput(long chatId, String group) throws Exception {
        String action = userAction.getOrDefault(chatId, "");
        if (action.isEmpty()) {
            sendMsg(chatId, "Выберите действие в меню.");
            return;
        }

        if ("near".equals(action)) {
            handleNearLesson(chatId, group);
        } else if ("tomorrow".equals(action)) {
            handleTomorrow(chatId, group);
        } else if ("all".equals(action)) {
            handleAllWeeksBoth(chatId, group);
        } else if ("day".equals(action)) {
            String day = userSelectedDay.get(chatId);
            handleDayFull(chatId, day, group);
        }

        userAction.remove(chatId);
        userSelectedDay.remove(chatId);
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText().trim();
            long chatId = update.getMessage().getChatId();

            try {
                if (text.equals("/start")) {
                    showMainMenu(chatId);
                } else if (text.equals("Ближайшее занятие")) {
                    askForGroup(chatId, "near");
                } else if (text.equals("Завтра")) {
                    askForGroup(chatId, "tomorrow");
                } else if (text.equals("Расписание по дням")) {
                    showDayMenu(chatId);
                } else if (text.equals("Вся неделя")) {
                    askForGroup(chatId, "all");
                } else if (text.equals("Назад")) {
                    showMainMenu(chatId);
                } else if (List.of("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота").contains(text)) {
                    askForGroupForDay(chatId, text);
                } else if (text.matches("\\d{4}")) {
                    handleGroupInput(chatId, text);
                } else {
                    sendMsg(chatId, "Нажмите кнопку в меню.");
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendMsg(chatId, "Ошибка: " + e.getMessage());
            }
        }
    }

    private void handleNearLesson(long chatId, String group) throws Exception {
        if (!group.matches("\\d{4}")) {
            sendMsg(chatId, "Группа: 4 цифры (например, 4354)");
            return;
        }

        List<DayTimetable> schedule = timetableAPIservice.getScheduleForGroup(group);
        LocalDateTime now = LocalDateTime.now();

        for (int days = 0; days < 14; days++) {
            LocalDateTime checkTime = now.plusDays(days);
            LocalDate checkDate = checkTime.toLocalDate();

            for (DayTimetable ds : schedule) {
                if (ds.date.equals(checkDate) && ds.lessons != null) {
                    for (Lessons lesson : ds.lessons) {
                        LocalDateTime lessonStart = ds.date.atTime(lesson.startTime);
                        if (lessonStart.isAfter(now)) {
                            // Проверяем, подходит ли занятие по неделе
                            boolean isEvenNow = DateUtils.isEvenWeek(ds.date);
                            if ("0".equals(lesson.week) ||
                                    ("1".equals(lesson.week) || "3".equals(lesson.week) ? !isEvenNow : isEvenNow)) {
                                sendMsg(chatId, "Ближайшее занятие:\n" + ds.date + " " + lesson.startTime + "\n" + lesson.toString());
                                return;
                            }
                        }
                    }
                }
            }
        }
        sendMsg(chatId, "Ближайшие 2 недели — занятий нет.");
    }

    private void handleTomorrow(long chatId, String group) throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.getDayOfWeek() == DayOfWeek.SUNDAY
                ? today.plusDays(1)
                : today.plusDays(1);

        List<DayTimetable> schedule = timetableAPIservice.getScheduleForGroup(group);
        for (DayTimetable ds : schedule) {
            if (ds.date.equals(tomorrow)) {
                if (ds.isEmpty()) {
                    sendMsg(chatId, "Завтра занятий нет.");
                } else {
                    sendMsg(chatId, "Завтра (" + tomorrow + "):\n" + formatDaySimple(ds));
                }
                return;
            }
        }
        sendMsg(chatId, "Расписание на завтра не загружено.");
    }

    private void handleDayFull(long chatId, String dayName, String group) throws Exception {
        DayOfWeek day = parseRussianDay(dayName);
        if (day == null) {
            sendMsg(chatId, "Неизвестный день");
            return;
        }

        LocalDate start = LocalDate.now();
        LocalDate end = start.plusWeeks(4);
        String url = String.format(
                "https://digital.etu.ru/api/mobile/schedule?groupNumber=%s&season=autumn&year=2025&joinWeeks=true&withURL=true",
                group
        );
        List<DayTimetable> fullSchedule = timetableAPIservice.getScheduleForGroup(group);

        List<DayTimetable> matchingDays = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 14; i++) {
            LocalDate candidate = today.plusDays(i);
            if (candidate.getDayOfWeek() == day) {
                for (DayTimetable ds : fullSchedule) {
                    if (ds.date.equals(candidate)) {
                        matchingDays.add(ds);
                        if (matchingDays.size() == 2) break;
                    }
                }
                if (matchingDays.size() == 2) break;
            }
        }

        if (matchingDays.isEmpty()) {
            sendMsg(chatId, "В " + dayName.toLowerCase() + " занятий нет.");
            return;
        }

        Map<String, List<Lessons>> timeSlots = new LinkedHashMap<>();
        for (DayTimetable ds : matchingDays) {
            if (ds.lessons != null) {
                for (Lessons l : ds.lessons) {
                    String key = l.startTime + "-" + l.endTime;
                    timeSlots.computeIfAbsent(key, k -> new ArrayList<>()).add(l);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(" ").append(dayName).append("\n\n");

        for (Map.Entry<String, List<Lessons>> slot : timeSlots.entrySet()) {
            List<Lessons> lessons = slot.getValue();
            if (lessons.size() == 1) {
                Lessons l = lessons.get(0);
                if ("0".equals(l.week)) {
                    sb.append("  • ").append(l.subject).append(" (").append(l.type).append("), ").append(l.teacher).append(", ").append(l.room).append("\n");
                } else {
                    String weekType = ("1".equals(l.week) || "3".equals(l.week)) ? "Нечётная" : "Чётная";
                    sb.append("  • ").append(weekType).append(" неделя: ").append(l.subject).append(" (").append(l.type).append("), ").append(l.teacher).append(", ").append(l.room).append("\n");
                }
            } else {
                Lessons odd = null, even = null;
                for (Lessons l : lessons) {
                    if ("1".equals(l.week) || "3".equals(l.week)) {
                        odd = l;
                    } else if ("2".equals(l.week) || "4".equals(l.week)) {
                        even = l;
                    }
                }
                if (odd != null) {
                    sb.append("  • Нечётная неделя: ").append(odd.subject).append(" (").append(odd.type).append("), ").append(odd.teacher).append(", ").append(odd.room).append("\n");
                }
                if (even != null) {
                    sb.append("  • Чётная неделя:       ").append(even.subject).append(" (").append(even.type).append("), ").append(even.teacher).append(", ").append(even.room).append("\n");
                }
            }
        }

        sendMsg(chatId, sb.toString());
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
        row1.add("Ближайшее занятие");
        row1.add("Завтра");
        rows.add(row1);

        KeyboardRow row2 = new KeyboardRow();
        row2.add("Расписание по дням");
        row2.add("Вся неделя");
        rows.add(row2);

        keyboard.setKeyboard(rows);
        keyboard.setResizeKeyboard(true);
        return keyboard;
    }

    private void showDayMenu(long chatId) {
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
        r1.add("Понедельник"); r1.add("Вторник"); r1.add("Среда");
        rows.add(r1);

        KeyboardRow r2 = new KeyboardRow();
        r2.add("Четверг"); r2.add("Пятница"); r2.add("Суббота");
        rows.add(r2);

        KeyboardRow r3 = new KeyboardRow();
        r3.add("Назад");
        rows.add(r3);

        keyboard.setKeyboard(rows);
        keyboard.setResizeKeyboard(true);
        return keyboard;
    }

    private DayOfWeek parseRussianDay(String name) {
        return switch (name) {
            case "Понедельник" -> DayOfWeek.MONDAY;
            case "Вторник" -> DayOfWeek.TUESDAY;
            case "Среда" -> DayOfWeek.WEDNESDAY;
            case "Четверг" -> DayOfWeek.THURSDAY;
            case "Пятница" -> DayOfWeek.FRIDAY;
            case "Суббота" -> DayOfWeek.SATURDAY;
            default -> null;
        };
    }

    private String formatDaySimple(DayTimetable ds) {
        if (ds.isEmpty()) return "Занятий нет.";
        StringJoiner sj = new StringJoiner("\n");
        for (Lessons l : ds.lessons) {
            sj.add("  • " + l.toString());
        }
        return sj.toString();
    }

    private void handleAllWeeksBoth(long chatId, String group) throws Exception {
        if (!group.matches("\\d{4}")) {
            sendMsg(chatId, "Группа должна быть из 4 цифр (например: 4354)");
            return;
        }

        // Получаем расписание на текущую неделю
        List<DayTimetable> schedule = timetableAPIservice.getScheduleForGroup(group);

        // Определяем ближайший понедельник для нечётной и чётной недели
        LocalDate today = LocalDate.now();
        LocalDate mondayOdd = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        if (DateUtils.isEvenWeek(mondayOdd)) {
            mondayOdd = mondayOdd.plusWeeks(1); // следующая нечётная
        }

        LocalDate mondayEven = mondayOdd.plusWeeks(1); // чётная неделя

        // Формируем вывод для нечётной недели
        StringBuilder response = new StringBuilder();
        response.append("📅 *Нечётная неделя* (").append(mondayOdd).append(" – ").append(mondayOdd.plusDays(5)).append("):\n\n");
        appendWeekSchedule(response, schedule, mondayOdd);

        response.append("\n📅 *Чётная неделя* (").append(mondayEven).append(" – ").append(mondayEven.plusDays(5)).append("):\n\n");
        appendWeekSchedule(response, schedule, mondayEven);

        sendMsg(chatId, response.toString());
    }

    private void appendWeekSchedule(StringBuilder sb, List<DayTimetable> schedule, LocalDate monday) {
        for (int i = 0; i < 6; i++) { // Пн–Сб
            LocalDate day = monday.plusDays(i);
            for (DayTimetable ds : schedule) {
                if (ds.date.equals(day) && !ds.isEmpty()) {
                    sb.append(ds.date).append(":\n");
                    for (Lessons lesson : ds.lessons) {
                        sb.append("  • ").append(lesson.toString()).append("\n");
                    }
                    sb.append("\n");
                    break;
                }
            }
        }
        if (sb.toString().endsWith("\n\n")) {
            sb.setLength(sb.length() - 2);
        }
    }
}