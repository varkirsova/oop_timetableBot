package ru.etu.timetable_bot;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.etu.timetable_bot.bot.TimetableBot;

@SpringBootApplication
public class TimetableBotApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(TimetableBotApplication.class, args);

        TimetableBot bot = ctx.getBean(TimetableBot.class);
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(bot);
            System.out.println("Бот работает");
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}