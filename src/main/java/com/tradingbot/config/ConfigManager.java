package com.tradingbot.config;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.apache.commons.configuration2.ex.ConfigurationException;
import java.io.File;

public class ConfigManager {
    private static ConfigManager instance;
    private Configuration config;

    private ConfigManager() {
        try {
            Configurations configs = new Configurations();
            config = configs.properties(new File("config.properties"));
        } catch (ConfigurationException e) {
            System.err.println("Errore nel caricamento della configurazione: " + e.getMessage());
            System.err.println("Assicurati che il file config.properties esista nella root del progetto.");
            System.exit(1);
        }
    }

    public static ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    public String getBotToken() {
        return config.getString("BOT_TOKEN");
    }

    public String getBotUsername() {
        return config.getString("BOT_USERNAME");
    }

    public String getAlphaVantageApiKey() {
        return config.getString("ALPHA_VANTAGE_API_KEY");
    }

    public String getDbPath() {
        return config.getString("DB_PATH", "trading_bot.db");
    }

    public double getInitialVirtualBalance() {
        return config.getDouble("INITIAL_VIRTUAL_BALANCE", 10000.00);
    }

    public String getDefaultCurrency() {
        return config.getString("DEFAULT_CURRENCY", "USD");
    }
}