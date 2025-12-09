package com;

import com.tradingbot.bot.TradingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) {
        System.out.println("💼 Avvio Trading Simulator Bot...");

        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            TradingBot bot = new TradingBot();
            botsApi.registerBot(bot);

            System.out.println("✅ Bot avviato con successo!");
            System.out.println("🤖 Username: " + bot.getBotUsername());
            System.out.println("📡 In attesa di messaggi...");
            System.out.println("💡 Gli utenti iniziano con un saldo virtuale di $10,000");

        } catch (TelegramApiException e) {
            System.err.println("❌ Errore nell'avvio del bot: " + e.getMessage());
            e.printStackTrace();
        }
    }
}