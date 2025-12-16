package com.tradingbot.database;

import com.tradingbot.config.ConfigManager;
import java.sql.*;
import java.time.LocalDateTime;

public class DatabaseManager {
    private static DatabaseManager instance;
    private Connection connection;

    private DatabaseManager() {
        try {
            String dbPath = ConfigManager.getInstance().getDbPath();
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            initDatabase();
        } catch (SQLException e) {
            System.err.println("Errore connessione database: " + e.getMessage());
        }
    }

    public static DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    private void initDatabase() throws SQLException {
        String createUsersTable = """
            CREATE TABLE IF NOT EXISTS users (
                user_id INTEGER PRIMARY KEY,
                username TEXT,
                first_name TEXT,
                last_name TEXT,
                virtual_balance REAL DEFAULT 10000.00,
                registration_date TEXT NOT NULL,
                last_activity TEXT NOT NULL,
                total_trades INTEGER DEFAULT 0,
                profitable_trades INTEGER DEFAULT 0
            )
        """;

        String createPortfolioTable = """
            CREATE TABLE IF NOT EXISTS portfolio (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                symbol TEXT NOT NULL,
                quantity REAL NOT NULL,
                avg_buy_price REAL NOT NULL,
                total_invested REAL NOT NULL,
                purchase_date TEXT NOT NULL,
                FOREIGN KEY (user_id) REFERENCES users(user_id),
                UNIQUE(user_id, symbol)
            )
        """;

        String createTransactionsTable = """
            CREATE TABLE IF NOT EXISTS transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                symbol TEXT NOT NULL,
                transaction_type TEXT NOT NULL,
                quantity REAL NOT NULL,
                price REAL NOT NULL,
                total_amount REAL NOT NULL,
                profit_loss REAL,
                timestamp TEXT NOT NULL,
                FOREIGN KEY (user_id) REFERENCES users(user_id)
            )
        """;

        String createWatchlistTable = """
            CREATE TABLE IF NOT EXISTS watchlist (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                symbol TEXT NOT NULL,
                added_date TEXT NOT NULL,
                alert_price REAL,
                FOREIGN KEY (user_id) REFERENCES users(user_id),
                UNIQUE(user_id, symbol)
            )
        """;

        String createStockPricesTable = """
            CREATE TABLE IF NOT EXISTS stock_prices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                symbol TEXT NOT NULL,
                price REAL NOT NULL,
                change_percent REAL,
                volume INTEGER,
                market_cap TEXT,
                last_updated TEXT NOT NULL
            )
        """;

        String createAlertsTable = """
            CREATE TABLE IF NOT EXISTS price_alerts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                symbol TEXT NOT NULL,
                target_price REAL NOT NULL,
                alert_type TEXT NOT NULL,
                is_active INTEGER DEFAULT 1,
                created_date TEXT NOT NULL,
                triggered_date TEXT,
                FOREIGN KEY (user_id) REFERENCES users(user_id)
            )
        """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createUsersTable);
            stmt.execute(createPortfolioTable);
            stmt.execute(createTransactionsTable);
            stmt.execute(createWatchlistTable);
            stmt.execute(createStockPricesTable);
            stmt.execute(createAlertsTable);
        }
    }

    public void addOrUpdateUser(long userId, String username, String firstName, String lastName) {
        String sql = """
            INSERT INTO users (user_id, username, first_name, last_name, virtual_balance, registration_date, last_activity)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET
                username = excluded.username,
                first_name = excluded.first_name,
                last_name = excluded.last_name,
                last_activity = excluded.last_activity
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, username);
            pstmt.setString(3, firstName);
            pstmt.setString(4, lastName);
            pstmt.setDouble(5, ConfigManager.getInstance().getInitialVirtualBalance());
            pstmt.setString(6, LocalDateTime.now().toString());
            pstmt.setString(7, LocalDateTime.now().toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Errore aggiornamento utente: " + e.getMessage());
        }
    }

    public double getUserBalance(long userId) {
        String sql = "SELECT virtual_balance FROM users WHERE user_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getDouble("virtual_balance");
            }
        } catch (SQLException e) {
            System.err.println("Errore recupero balance: " + e.getMessage());
        }
        return 0.0;
    }

    public void updateUserBalance(long userId, double newBalance) {
        String sql = "UPDATE users SET virtual_balance = ? WHERE user_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setDouble(1, newBalance);
            pstmt.setLong(2, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Errore aggiornamento balance: " + e.getMessage());
        }
    }

    public void buyStock(long userId, String symbol, double quantity, double price) {
        double totalCost = quantity * price;

        // Aggiorna o inserisci nel portfolio
        String portfolioSql = """
            INSERT INTO portfolio (user_id, symbol, quantity, avg_buy_price, total_invested, purchase_date)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(user_id, symbol) DO UPDATE SET
                quantity = quantity + excluded.quantity,
                total_invested = total_invested + excluded.total_invested,
                avg_buy_price = (total_invested + excluded.total_invested) / (quantity + excluded.quantity)
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(portfolioSql)) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, symbol);
            pstmt.setDouble(3, quantity);
            pstmt.setDouble(4, price);
            pstmt.setDouble(5, totalCost);
            pstmt.setString(6, LocalDateTime.now().toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Errore acquisto stock: " + e.getMessage());
            return;
        }

        // Registra la transazione
        recordTransaction(userId, symbol, "BUY", quantity, price, totalCost, null);

        // Aggiorna il balance
        double currentBalance = getUserBalance(userId);
        updateUserBalance(userId, currentBalance - totalCost);

        // Incrementa contatore trades
        incrementTotalTrades(userId);
    }

    public boolean sellStock(long userId, String symbol, double quantity, double currentPrice) {
        // Verifica se l'utente ha abbastanza azioni
        String checkSql = "SELECT quantity, avg_buy_price FROM portfolio WHERE user_id = ? AND symbol = ?";
        double ownedQuantity = 0;
        double avgBuyPrice = 0;

        try (PreparedStatement pstmt = connection.prepareStatement(checkSql)) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, symbol);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                ownedQuantity = rs.getDouble("quantity");
                avgBuyPrice = rs.getDouble("avg_buy_price");
            }
        } catch (SQLException e) {
            System.err.println("Errore verifica portfolio: " + e.getMessage());
            return false;
        }

        if (ownedQuantity < quantity) {
            return false;
        }

        double totalRevenue = quantity * currentPrice;
        double profitLoss = (currentPrice - avgBuyPrice) * quantity;

        // Aggiorna il portfolio
        if (ownedQuantity == quantity) {
            // Vendi tutto
            String deleteSql = "DELETE FROM portfolio WHERE user_id = ? AND symbol = ?";
            try (PreparedStatement pstmt = connection.prepareStatement(deleteSql)) {
                pstmt.setLong(1, userId);
                pstmt.setString(2, symbol);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                System.err.println("Errore vendita totale: " + e.getMessage());
                return false;
            }
        } else {
            // Vendi parzialmente
            String updateSql = "UPDATE portfolio SET quantity = quantity - ? WHERE user_id = ? AND symbol = ?";
            try (PreparedStatement pstmt = connection.prepareStatement(updateSql)) {
                pstmt.setDouble(1, quantity);
                pstmt.setLong(2, userId);
                pstmt.setString(3, symbol);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                System.err.println("Errore vendita parziale: " + e.getMessage());
                return false;
            }
        }

        // Registra la transazione
        recordTransaction(userId, symbol, "SELL", quantity, currentPrice, totalRevenue, profitLoss);

        // Aggiorna il balance
        double currentBalance = getUserBalance(userId);
        updateUserBalance(userId, currentBalance + totalRevenue);

        // Incrementa contatori
        incrementTotalTrades(userId);
        if (profitLoss > 0) {
            incrementProfitableTrades(userId);
        }

        return true;
    }

    private void recordTransaction(long userId, String symbol, String type, double quantity,
                                   double price, double totalAmount, Double profitLoss) {
        String sql = """
            INSERT INTO transactions (user_id, symbol, transaction_type, quantity, price, total_amount, profit_loss, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, symbol);
            pstmt.setString(3, type);
            pstmt.setDouble(4, quantity);
            pstmt.setDouble(5, price);
            pstmt.setDouble(6, totalAmount);
            if (profitLoss != null) {
                pstmt.setDouble(7, profitLoss);
            } else {
                pstmt.setNull(7, Types.DOUBLE);
            }
            pstmt.setString(8, LocalDateTime.now().toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Errore registrazione transazione: " + e.getMessage());
        }
    }

    public String getPortfolio(long userId, java.util.Map<String, Double> currentPrices) {
        String sql = "SELECT symbol, quantity, avg_buy_price, total_invested FROM portfolio WHERE user_id = ?";
        StringBuilder result = new StringBuilder("📊 IL TUO PORTFOLIO:\n\n");
        double totalValue = 0;
        double totalInvested = 0;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            ResultSet rs = pstmt.executeQuery();

            boolean hasStocks = false;
            while (rs.next()) {
                hasStocks = true;
                String symbol = rs.getString("symbol");
                double quantity = rs.getDouble("quantity");
                double avgBuyPrice = rs.getDouble("avg_buy_price");
                double invested = rs.getDouble("total_invested");

                Double currentPrice = currentPrices.get(symbol);
                if (currentPrice != null) {
                    double currentValue = quantity * currentPrice;
                    double profitLoss = currentValue - invested;
                    double profitLossPercent = (profitLoss / invested) * 100;

                    totalValue += currentValue;
                    totalInvested += invested;

                    String profitEmoji = profitLoss >= 0 ? "📈" : "📉";

                    result.append(String.format("%s %s\n", profitEmoji, symbol));
                    result.append(String.format("Quantità: %.2f\n", quantity));
                    result.append(String.format("Prezzo medio: $%.2f\n", avgBuyPrice));
                    result.append(String.format("Prezzo attuale: $%.2f\n", currentPrice));
                    result.append(String.format("Valore: $%.2f\n", currentValue));
                    result.append(String.format("P/L: $%.2f (%.2f%%)\n\n", profitLoss, profitLossPercent));
                }
            }

            if (!hasStocks) {
                return "📊 Portfolio vuoto. Inizia a investire con /compra!";
            }

            double totalProfitLoss = totalValue - totalInvested;
            double totalProfitLossPercent = totalInvested > 0 ? (totalProfitLoss / totalInvested) * 100 : 0;

            result.append("━━━━━━━━━━━━━━━━━━━━\n");
            result.append(String.format("💰 Valore totale: $%.2f\n", totalValue));
            result.append(String.format("💵 Investito: $%.2f\n", totalInvested));
            result.append(String.format("📊 P/L totale: $%.2f (%.2f%%)\n", totalProfitLoss, totalProfitLossPercent));
            result.append(String.format("💳 Cash disponibile: $%.2f", getUserBalance(userId)));

        } catch (SQLException e) {
            return "Errore nel recupero del portfolio";
        }

        return result.toString();
    }

    public String getTransactionHistory(long userId, int limit) {
        String sql = """
            SELECT symbol, transaction_type, quantity, price, total_amount, profit_loss, timestamp 
            FROM transactions WHERE user_id = ? 
            ORDER BY timestamp DESC LIMIT ?
        """;

        StringBuilder result = new StringBuilder("📜 STORICO TRANSAZIONI:\n\n");

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setInt(2, limit);
            ResultSet rs = pstmt.executeQuery();

            boolean hasTransactions = false;
            while (rs.next()) {
                hasTransactions = true;
                String symbol = rs.getString("symbol");
                String type = rs.getString("transaction_type");
                double quantity = rs.getDouble("quantity");
                double price = rs.getDouble("price");
                double totalAmount = rs.getDouble("total_amount");
                Double profitLoss = rs.getObject("profit_loss", Double.class);
                String timestamp = rs.getString("timestamp").substring(0, 16).replace("T", " ");

                String emoji = type.equals("BUY") ? "🟢" : "🔴";
                result.append(String.format("%s %s %s\n", emoji, type, symbol));
                result.append(String.format("Quantità: %.2f @ $%.2f\n", quantity, price));
                result.append(String.format("Totale: $%.2f\n", totalAmount));
                if (profitLoss != null) {
                    String plEmoji = profitLoss >= 0 ? "💚" : "❤️";
                    result.append(String.format("%s P/L: $%.2f\n", plEmoji, profitLoss));
                }
                result.append(String.format("📅 %s\n\n", timestamp));
            }

            if (!hasTransactions) {
                return "📜 Nessuna transazione effettuata.";
            }

        } catch (SQLException e) {
            return "Errore nel recupero dello storico";
        }

        return result.toString();
    }

    public void addToWatchlist(long userId, String symbol) {
        String sql = "INSERT OR IGNORE INTO watchlist (user_id, symbol, added_date) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, symbol);
            pstmt.setString(3, LocalDateTime.now().toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Errore aggiunta watchlist: " + e.getMessage());
        }
    }

    public String getWatchlist(long userId) {
        String sql = "SELECT symbol FROM watchlist WHERE user_id = ?";
        StringBuilder result = new StringBuilder("⭐ LA TUA WATCHLIST:\n\n");

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            ResultSet rs = pstmt.executeQuery();

            boolean hasItems = false;
            while (rs.next()) {
                hasItems = true;
                result.append("📌 ").append(rs.getString("symbol")).append("\n");
            }

            if (!hasItems) {
                return "⭐ Watchlist vuota. Aggiungi simboli con /watch [SIMBOLO]";
            }

        } catch (SQLException e) {
            return "Errore nel recupero della watchlist";
        }

        return result.toString();
    }

    private void incrementTotalTrades(long userId) {
        String sql = "UPDATE users SET total_trades = total_trades + 1 WHERE user_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Errore incremento trades: " + e.getMessage());
        }
    }

    private void incrementProfitableTrades(long userId) {
        String sql = "UPDATE users SET profitable_trades = profitable_trades + 1 WHERE user_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Errore incremento profitable trades: " + e.getMessage());
        }
    }

    public String getUserStats(long userId) {
        String sql = """
            SELECT virtual_balance, total_trades, profitable_trades, registration_date 
            FROM users WHERE user_id = ?
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                double balance = rs.getDouble("virtual_balance");
                int totalTrades = rs.getInt("total_trades");
                int profitableTrades = rs.getInt("profitable_trades");
                String regDate = rs.getString("registration_date").substring(0, 10);

                double winRate = totalTrades > 0 ? (profitableTrades * 100.0 / totalTrades) : 0;

                return String.format("""
                    📊 LE TUE STATISTICHE:
                    
                    💰 Balance: $%.2f
                    📈 Trades totali: %d
                    ✅ Trades profittevoli: %d
                    📊 Win Rate: %.1f%%
                    📅 Membro dal: %s
                    """, balance, totalTrades, profitableTrades, winRate, regDate);
            }
        } catch (SQLException e) {
            System.err.println("Errore statistiche utente: " + e.getMessage());
        }

        return "Errore nel recupero delle statistiche";
    }

    public Connection getConnection() {
        return connection;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            System.err.println("Errore chiusura database: " + e.getMessage());
        }
    }
}