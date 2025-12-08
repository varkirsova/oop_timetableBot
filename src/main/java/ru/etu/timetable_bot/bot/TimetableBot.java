package ru.etu.timetable_bot.bot;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.etu.timetable_bot.api.TimetableAPIservice;
import ru.etu.timetable_bot.model.DayTimetable;
import ru.etu.timetable_bot.model.Lessons;

import java.util.List;
import java.util.StringJoiner;

@Component
public class TimetableBot extends TelegramLongPollingBot {

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
            Message msg = update.getMessage();
            long chatId = msg.getChatId();
            String text = msg.getText().trim();

            try {
                if (text.equals("/start")) {
                    sendMsg(chatId, "Привет! Я бот расписания ЛЭТИ.\n" +
                            "Примеры:\n" +
                            "/near_lesson 4354\n" +
                            "/tomorrow 4354");
                } else if (text.startsWith("/near_lesson ")) {
                    String group = text.substring("/near_lesson ".length()).trim();
                    handleNearLesson(chatId, group);
                } else if (text.startsWith("/tomorrow ")) {
                    String group = text.substring("/tomorrow ".length()).trim();
                    handleTomorrow(chatId, group);
                } else {
                    sendMsg(chatId, "Неизвестная команда. Напишите /start.");
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendMsg(chatId, "Ошибка: " + e.getMessage());
            }
        }
    }

    private void handleNearLesson(long chatId, String group) throws Exception {
        if (!group.matches("\\d{4}")) {
            sendMsg(chatId, "Группа должна быть из 4 цифр (например: 4354)");
            return;
        }

        List<DayTimetable> schedules = timetableAPIservice.getScheduleForGroup(group);
        for (DayTimetable ds : schedules) {
            if (!ds.isEmpty()) {
                sendMsg(chatId, "Ближайшее занятие:\n" + formatDay(ds));
                return;
            }
        }
        sendMsg(chatId, "Занятий в ближайшие дни не найдено.");
    }

    private void handleTomorrow(long chatId, String group) throws Exception {
        if (!group.matches("\\d{4}")) {
            sendMsg(chatId, "Группа должна быть из 4 цифр (например: 4354)");
            return;
        }

        List<DayTimetable> schedules = timetableAPIservice.getScheduleForGroup(group);
        if (schedules.size() > 1) {
            DayTimetable tomorrow = schedules.get(1);
            if (!tomorrow.isEmpty()) {
                sendMsg(chatId, "Завтра:\n" + formatDay(tomorrow));
                return;
            }
        }
        sendMsg(chatId, "Завтра занятий нет.");
    }

    private String formatDay(DayTimetable ds) {
        if (ds.isEmpty()) return ds.date + ": занятий нет.";
        StringJoiner sj = new StringJoiner("\n");
        sj.add(ds.date + ":");
        for (Lessons l : ds.lessons) {
            sj.add("  • " + l.toString());
        }
        return sj.toString();
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
}