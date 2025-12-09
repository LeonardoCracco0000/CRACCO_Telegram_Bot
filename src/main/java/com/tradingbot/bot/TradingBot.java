package com.tradingbot.bot;

import com.tradingbot.api.AlphaVantageClient;
import com.tradingbot.config.ConfigManager;
import com.tradingbot.database.DatabaseManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class TradingBot extends TelegramLongPollingBot {
    private final ConfigManager config;
    private final DatabaseManager db;
    private final AlphaVantageClient api;

    public TradingBot() {
        this.config = ConfigManager.getInstance();
        this.db = DatabaseManager.getInstance();
        this.api = AlphaVantageClient.getInstance();
    }

    @Override
    public String getBotUsername() {
        return config.getBotUsername();
    }

    @Override
    public String getBotToken() {
        return config.getBotToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            long chatId = update.getMessage().getChatId();
            String messageText = update.getMessage().getText();
            long userId = update.getMessage().getFrom().getId();
            String username = update.getMessage().getFrom().getUserName();
            String firstName = update.getMessage().getFrom().getFirstName();
            String lastName = update.getMessage().getFrom().getLastName();

            db.addOrUpdateUser(userId, username, firstName, lastName);

            String response = handleCommand(messageText, userId);
            sendMessage(chatId, response);
        }
    }

    private String handleCommand(String command, long userId) {
        String[] parts = command.split(" ");
        String cmd = parts[0].toLowerCase();

        return switch (cmd) {
            case "/start" -> getWelcomeMessage();
            case "/help" -> getHelpMessage();
            case "/prezzo" -> parts.length < 2 ?
                    "❌ Specifica il simbolo: /prezzo AAPL" :
                    getStockPrice(parts[1].toUpperCase());
            case "/info" -> parts.length < 2 ?
                    "❌ Specifica il simbolo: /info AAPL" :
                    getCompanyInfo(parts[1].toUpperCase());
            case "/compra" -> parts.length < 3 ?
                    "❌ Usa: /compra SIMBOLO QUANTITA\nEsempio: /compra AAPL 10" :
                    buyStock(userId, parts[1].toUpperCase(), parts[2]);
            case "/vendi" -> parts.length < 3 ?
                    "❌ Usa: /vendi SIMBOLO QUANTITA\nEsempio: /vendi AAPL 5" :
                    sellStock(userId, parts[1].toUpperCase(), parts[2]);
            case "/portfolio" -> getPortfolio(userId);
            case "/balance" -> getBalance(userId);
            case "/storico" -> getHistory(userId);
            case "/watch" -> parts.length < 2 ?
                    "❌ Usa: /watch SIMBOLO\nEsempio: /watch TSLA" :
                    addToWatchlist(userId, parts[1].toUpperCase());
            case "/watchlist" -> getWatchlist(userId);
            case "/stats" -> db.getUserStats(userId);
            case "/cerca" -> parts.length < 2 ?
                    "❌ Usa: /cerca PAROLA_CHIAVE\nEsempio: /cerca Apple" :
                    searchSymbol(String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)));
            case "/top" -> getTopStocks();
            case "/reset" -> resetAccount(userId);
            default -> "❓ Comando non riconosciuto. Usa /help per vedere tutti i comandi.";
        };
    }

    private String getWelcomeMessage() {
        return String.format("""
                💼 Benvenuto nel Trading Simulator Bot! 📈
                
                Inizia a fare trading virtuale con $%.2f!
                
                🎯 Cosa puoi fare:
                • Comprare e vendere azioni reali (con soldi virtuali)
                • Monitorare il tuo portfolio
                • Vedere prezzi in tempo reale
                • Analizzare le tue performance
                
                💡 Suggerimento: Inizia con /prezzo AAPL per vedere il prezzo Apple!
                
                Usa /help per tutti i comandi disponibili.
                """, config.getInitialVirtualBalance());
    }

    private String getHelpMessage() {
        return """
                📚 COMANDI DISPONIBILI:
                
                📊 QUOTAZIONI:
                /prezzo [SIMBOLO] - Prezzo attuale di un'azione
                /info [SIMBOLO] - Informazioni dettagliate azienda
                /cerca [NOME] - Cerca simbolo per nome azienda
                /top - Top azioni popolari
                
                💰 TRADING:
                /compra [SIMBOLO] [QTÀ] - Compra azioni
                /vendi [SIMBOLO] [QTÀ] - Vendi azioni
                /balance - Mostra il tuo saldo disponibile
                
                📈 PORTFOLIO:
                /portfolio - Vedi il tuo portfolio completo
                /storico - Storico delle transazioni
                /stats - Le tue statistiche di trading
                
                ⭐ WATCHLIST:
                /watch [SIMBOLO] - Aggiungi alla watchlist
                /watchlist - Mostra la tua watchlist
                
                🔄 ALTRO:
                /reset - Resetta il tuo account (riparti da capo)
                /help - Mostra questo messaggio
                
                💡 Esempi:
                /prezzo AAPL
                /compra TSLA 5
                /vendi MSFT 2
                """;
    }

    private String getStockPrice(String symbol) {
        try {
            JsonObject quote = api.getQuote(symbol);

            if (quote.has("cached")) {
                double price = quote.get("price").getAsDouble();
                return String.format("""
                        📊 %s
                        💵 Prezzo: $%.2f
                        
                        ⚡ Dati dalla cache (aggiornati max 1 min fa)
                        """, symbol, price);
            }

            double price = Double.parseDouble(quote.get("05. price").getAsString());
            double change = Double.parseDouble(quote.get("09. change").getAsString());
            double changePercent = Double.parseDouble(quote.get("10. change percent").getAsString().replace("%", ""));
            long volume = Long.parseLong(quote.get("06. volume").getAsString());

            String changeEmoji = change >= 0 ? "📈" : "📉";
            String changeColor = change >= 0 ? "🟢" : "🔴";

            return String.format("""
                    📊 %s
                    💵 Prezzo: $%.2f
                    %s Variazione: %s$%.2f (%.2f%%)
                    📊 Volume: %,d
                    
                    💡 Usa /compra %s [quantità] per acquistare
                    """, symbol, price, changeEmoji, changeColor, Math.abs(change),
                    changePercent, volume, symbol);

        } catch (IOException e) {
            if (e.getMessage().contains("API limit")) {
                return "⚠️ Limite API raggiunto. Riprova tra qualche minuto.\n" +
                        "Il piano gratuito ha un limite di 25 richieste/giorno.";
            }
            return "❌ Errore: " + e.getMessage() + "\nVerifica che il simbolo sia corretto.";
        }
    }

    private String getCompanyInfo(String symbol) {
        try {
            JsonObject overview = api.getCompanyOverview(symbol);

            String name = overview.get("Name").getAsString();
            String sector = overview.has("Sector") ? overview.get("Sector").getAsString() : "N/D";
            String industry = overview.has("Industry") ? overview.get("Industry").getAsString() : "N/D";
            String marketCap = overview.has("MarketCapitalization") ?
                    formatMarketCap(overview.get("MarketCapitalization").getAsString()) : "N/D";
            String peRatio = overview.has("PERatio") && !overview.get("PERatio").getAsString().equals("None") ?
                    overview.get("PERatio").getAsString() : "N/D";
            String description = overview.has("Description") ?
                    overview.get("Description").getAsString() : "Descrizione non disponibile";

            // Limita la descrizione a 300 caratteri
            if (description.length() > 300) {
                description = description.substring(0, 297) + "...";
            }

            return String.format("""
                    🏢 %s (%s)
                    
                    📊 Settore: %s
                    🏭 Industria: %s
                    💰 Market Cap: %s
                    📈 P/E Ratio: %s
                    
                    📝 Descrizione:
                    %s
                    
                    💡 Usa /prezzo %s per vedere il prezzo attuale
                    """, name, symbol, sector, industry, marketCap, peRatio, description, symbol);

        } catch (IOException e) {
            if (e.getMessage().contains("API limit")) {
                return "⚠️ Limite API raggiunto. Riprova tra qualche minuto.";
            }
            return "❌ Informazioni non disponibili per questo simbolo.";
        }
    }

    private String buyStock(long userId, String symbol, String quantityStr) {
        try {
            double quantity = Double.parseDouble(quantityStr);

            if (quantity <= 0) {
                return "❌ La quantità deve essere maggiore di 0.";
            }

            double currentPrice = api.getCurrentPrice(symbol);
            double totalCost = currentPrice * quantity;
            double userBalance = db.getUserBalance(userId);

            if (totalCost > userBalance) {
                return String.format("""
                        ❌ Fondi insufficienti!
                        
                        💵 Costo totale: $%.2f
                        💳 Saldo disponibile: $%.2f
                        💰 Mancano: $%.2f
                        """, totalCost, userBalance, totalCost - userBalance);
            }

            db.buyStock(userId, symbol, quantity, currentPrice);

            return String.format("""
                    ✅ ACQUISTO COMPLETATO!
                    
                    📊 %s
                    📦 Quantità: %.2f
                    💵 Prezzo: $%.2f
                    💰 Totale: $%.2f
                    💳 Nuovo saldo: $%.2f
                    
                    💡 Usa /portfolio per vedere il tuo portfolio
                    """, symbol, quantity, currentPrice, totalCost,
                    userBalance - totalCost);

        } catch (NumberFormatException e) {
            return "❌ Quantità non valida. Usa un numero (es. 10 o 5.5)";
        } catch (IOException e) {
            if (e.getMessage().contains("API limit")) {
                return "⚠️ Limite API raggiunto. Riprova tra qualche minuto.";
            }
            return "❌ Errore: " + e.getMessage();
        }
    }

    private String sellStock(long userId, String symbol, String quantityStr) {
        try {
            double quantity = Double.parseDouble(quantityStr);

            if (quantity <= 0) {
                return "❌ La quantità deve essere maggiore di 0.";
            }

            double currentPrice = api.getCurrentPrice(symbol);

            boolean success = db.sellStock(userId, symbol, quantity, currentPrice);

            if (!success) {
                return String.format("""
                        ❌ VENDITA FALLITA!
                        
                        Non possiedi abbastanza azioni di %s.
                        Controlla il tuo portfolio con /portfolio
                        """, symbol);
            }

            double totalRevenue = currentPrice * quantity;
            double newBalance = db.getUserBalance(userId);

            return String.format("""
                    ✅ VENDITA COMPLETATA!
                    
                    📊 %s
                    📦 Quantità: %.2f
                    💵 Prezzo: $%.2f
                    💰 Incassato: $%.2f
                    💳 Nuovo saldo: $%.2f
                    
                    💡 Usa /storico per vedere tutte le transazioni
                    """, symbol, quantity, currentPrice, totalRevenue, newBalance);

        } catch (NumberFormatException e) {
            return "❌ Quantità non valida. Usa un numero (es. 10 o 5.5)";
        } catch (IOException e) {
            if (e.getMessage().contains("API limit")) {
                return "⚠️ Limite API raggiunto. Riprova tra qualche minuto.";
            }
            return "❌ Errore: " + e.getMessage();
        }
    }

    private String getPortfolio(long userId) {
        try {
            // Ottieni tutti i simboli nel portfolio dell'utente
            String portfolioData = db.getPortfolio(userId, new HashMap<>());

            if (portfolioData.contains("Portfolio vuoto")) {
                return portfolioData;
            }

            // Estrai i simboli e ottieni i prezzi correnti
            // Questo è semplificato - in produzione dovresti ottimizzare
            Map<String, Double> currentPrices = new HashMap<>();
            // Per ora ritorna il portfolio con prezzi cached o richiedi manualmente

            return db.getPortfolio(userId, currentPrices);

        } catch (Exception e) {
            return "❌ Errore nel recupero del portfolio: " + e.getMessage();
        }
    }

    private String getBalance(long userId) {
        double balance = db.getUserBalance(userId);
        return String.format("""
                💳 SALDO DISPONIBILE
                
                💰 $%.2f
                
                💡 Usa /compra per investire
                💡 Usa /portfolio per vedere i tuoi investimenti
                """, balance);
    }

    private String getHistory(long userId) {
        return db.getTransactionHistory(userId, 10);
    }

    private String addToWatchlist(long userId, String symbol) {
        try {
            // Verifica che il simbolo esista
            api.getCurrentPrice(symbol);
            db.addToWatchlist(userId, symbol);

            return String.format("""
                    ⭐ %s aggiunto alla watchlist!
                    
                    💡 Usa /watchlist per vedere tutti i simboli salvati
                    💡 Usa /prezzo %s per vedere il prezzo
                    """, symbol, symbol);

        } catch (IOException e) {
            return "❌ Simbolo non valido o non trovato.";
        }
    }

    private String getWatchlist(long userId) {
        return db.getWatchlist(userId);
    }

    private String searchSymbol(String keywords) {
        try {
            JsonObject result = api.searchSymbol(keywords);
            JsonArray matches = result.getAsJsonArray("bestMatches");

            if (matches == null || matches.size() == 0) {
                return "❌ Nessun risultato trovato per: " + keywords;
            }

            StringBuilder response = new StringBuilder("🔍 RISULTATI RICERCA:\n\n");

            for (int i = 0; i < Math.min(matches.size(), 5); i++) {
                JsonObject match = matches.get(i).getAsJsonObject();
                String symbol = match.get("1. symbol").getAsString();
                String name = match.get("2. name").getAsString();
                String type = match.get("3. type").getAsString();
                String region = match.get("4. region").getAsString();

                response.append(String.format("📊 %s - %s\n", symbol, name));
                response.append(String.format("   Tipo: %s | Regione: %s\n\n", type, region));
            }

            response.append("💡 Usa /prezzo [SIMBOLO] per vedere il prezzo");

            return response.toString();

        } catch (IOException e) {
            return "❌ Errore nella ricerca: " + e.getMessage();
        }
    }

    private String getTopStocks() {
        return """
                🔥 TOP AZIONI POPOLARI:
                
                🍎 AAPL - Apple Inc.
                💻 MSFT - Microsoft Corporation
                🚗 TSLA - Tesla Inc.
                📦 AMZN - Amazon.com Inc.
                🔍 GOOGL - Alphabet Inc. (Google)
                💳 V - Visa Inc.
                🎮 NVDA - NVIDIA Corporation
                ☕ SBUX - Starbucks Corporation
                🎬 DIS - The Walt Disney Company
                ✈️ BA - Boeing Company
                
                💡 Usa /prezzo [SIMBOLO] per vedere il prezzo
                💡 Usa /info [SIMBOLO] per info dettagliate
                """;
    }

    private String resetAccount(long userId) {
        double initialBalance = config.getInitialVirtualBalance();
        db.updateUserBalance(userId, initialBalance);

        return String.format("""
                🔄 ACCOUNT RESETTATO!
                
                💰 Nuovo saldo: $%.2f
                
                ⚠️ Nota: Il portfolio e lo storico non sono stati cancellati,
                ma puoi ricominciare da capo con un nuovo saldo.
                
                💡 Buon trading!
                """, initialBalance);
    }

    private String formatMarketCap(String marketCapStr) {
        try {
            long marketCap = Long.parseLong(marketCapStr);

            if (marketCap >= 1_000_000_000_000L) {
                return String.format("$%.2fT", marketCap / 1_000_000_000_000.0);
            } else if (marketCap >= 1_000_000_000L) {
                return String.format("$%.2fB", marketCap / 1_000_000_000.0);
            } else if (marketCap >= 1_000_000L) {
                return String.format("$%.2fM", marketCap / 1_000_000.0);
            } else {
                return String.format("$%,d", marketCap);
            }
        } catch (NumberFormatException e) {
            return marketCapStr;
        }
    }

    private void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            System.err.println("Errore invio messaggio: " + e.getMessage());
        }
    }
}