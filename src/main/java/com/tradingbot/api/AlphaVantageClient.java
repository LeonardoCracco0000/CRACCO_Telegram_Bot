package com.tradingbot.api;

import com.tradingbot.config.ConfigManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class AlphaVantageClient {
    private static AlphaVantageClient instance;
    private final OkHttpClient client;
    private final String apiKey;
    private static final String BASE_URL = "https://www.alphavantage.co/query";

    // Cache per limitare le chiamate API
    private final Map<String, CachedPrice> priceCache = new HashMap<>();
    private static final long CACHE_DURATION = 60000; // 1 minuto

    private static class CachedPrice {
        double price;
        long timestamp;

        CachedPrice(double price) {
            this.price = price;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_DURATION;
        }
    }

    private AlphaVantageClient() {
        this.client = new OkHttpClient();
        this.apiKey = ConfigManager.getInstance().getAlphaVantageApiKey();
    }

    public static AlphaVantageClient getInstance() {
        if (instance == null) {
            instance = new AlphaVantageClient();
        }
        return instance;
    }

    public JsonObject getQuote(String symbol) throws IOException {
        // Controlla la cache
        CachedPrice cached = priceCache.get(symbol);
        if (cached != null && !cached.isExpired()) {
            JsonObject cachedResult = new JsonObject();
            cachedResult.addProperty("price", cached.price);
            cachedResult.addProperty("cached", true);
            return cachedResult;
        }

        String url = String.format("%s?function=GLOBAL_QUOTE&symbol=%s&apikey=%s",
                BASE_URL, symbol, apiKey);

        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Richiesta fallita: " + response);
            }

            String responseBody = response.body().string();
            JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();

            // Controlla se c'è un errore o limite raggiunto
            if (jsonResponse.has("Note") || jsonResponse.has("Error Message")) {
                throw new IOException("API limit raggiunto o simbolo non valido");
            }

            JsonObject globalQuote = jsonResponse.getAsJsonObject("Global Quote");
            if (globalQuote == null || globalQuote.size() == 0) {
                throw new IOException("Simbolo non trovato o dati non disponibili");
            }

            // Estrai il prezzo e metti in cache
            double price = Double.parseDouble(globalQuote.get("05. price").getAsString());
            priceCache.put(symbol, new CachedPrice(price));

            return globalQuote;
        }
    }

    public JsonObject getIntradayData(String symbol, String interval) throws IOException {
        String url = String.format("%s?function=TIME_SERIES_INTRADAY&symbol=%s&interval=%s&apikey=%s",
                BASE_URL, symbol, interval, apiKey);

        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Richiesta fallita: " + response);
            }

            String responseBody = response.body().string();
            return JsonParser.parseString(responseBody).getAsJsonObject();
        }
    }

    public JsonObject getDailyData(String symbol) throws IOException {
        String url = String.format("%s?function=TIME_SERIES_DAILY&symbol=%s&apikey=%s",
                BASE_URL, symbol, apiKey);

        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Richiesta fallita: " + response);
            }

            String responseBody = response.body().string();
            return JsonParser.parseString(responseBody).getAsJsonObject();
        }
    }

    public JsonObject getCompanyOverview(String symbol) throws IOException {
        String url = String.format("%s?function=OVERVIEW&symbol=%s&apikey=%s",
                BASE_URL, symbol, apiKey);

        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Richiesta fallita: " + response);
            }

            String responseBody = response.body().string();
            JsonObject overview = JsonParser.parseString(responseBody).getAsJsonObject();

            if (overview.has("Note") || overview.size() == 0) {
                throw new IOException("Dati non disponibili per questo simbolo");
            }

            return overview;
        }
    }

    public JsonObject searchSymbol(String keywords) throws IOException {
        String url = String.format("%s?function=SYMBOL_SEARCH&keywords=%s&apikey=%s",
                BASE_URL, keywords, apiKey);

        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Richiesta fallita: " + response);
            }

            String responseBody = response.body().string();
            return JsonParser.parseString(responseBody).getAsJsonObject();
        }
    }

    public double getCurrentPrice(String symbol) throws IOException {
        JsonObject quote = getQuote(symbol);

        if (quote.has("cached")) {
            return quote.get("price").getAsDouble();
        }

        return Double.parseDouble(quote.get("05. price").getAsString());
    }

    public Map<String, Double> getCurrentPrices(String... symbols) {
        Map<String, Double> prices = new HashMap<>();

        for (String symbol : symbols) {
            try {
                double price = getCurrentPrice(symbol);
                prices.put(symbol, price);
            } catch (IOException e) {
                System.err.println("Errore recupero prezzo per " + symbol + ": " + e.getMessage());
                // Usa prezzo dalla cache se disponibile
                CachedPrice cached = priceCache.get(symbol);
                if (cached != null) {
                    prices.put(symbol, cached.price);
                }
            }
        }

        return prices;
    }
}