package de.aruru.territory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

final class TerritoryDatabase implements AutoCloseable {
    record Row(String key, UUID ownerUuid, String ownerName, String territoryName) {}

    private final Connection local;
    private Connection remote;
    private final Properties remoteProperties;
    private boolean remoteAvailable;

    private TerritoryDatabase(Connection local, Connection remote, Properties remoteProperties) {
        this.local = local;
        this.remote = remote;
        this.remoteProperties = remoteProperties;
        this.remoteAvailable = remote != null;
    }

    static TerritoryDatabase open() {
        try {
            Path directory = Path.of("config", "territory");
            Files.createDirectories(directory);
            Properties properties = loadProperties(directory.resolve("database.properties"));
            Connection local = DriverManager.getConnection("jdbc:sqlite:" +
                    directory.resolve(properties.getProperty("file", "territory.db")));
            TerritoryDatabase database = new TerritoryDatabase(local, openRemote(properties), properties);
            database.createSchema(local);
            if (database.remote != null) database.createSchema(database.remote);
            database.syncRemote();
            return database;
        } catch (IOException | SQLException ex) {
            throw new IllegalStateException("Failed to open local territory database", ex);
        }
    }

    private static Properties loadProperties(Path configFile) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(configFile)) {
            try (var reader = Files.newBufferedReader(configFile)) {
                properties.load(reader);
            }
        } else {
            properties.setProperty("type", "sqlite");
            properties.setProperty("file", "territory.db");
            try (var writer = Files.newBufferedWriter(configFile)) {
                properties.store(writer, "Territory database settings");
            }
        }
        return properties;
    }

    private static Connection openRemote(Properties properties) {
        String type = properties.getProperty("type", "sqlite").trim().toLowerCase(Locale.ROOT);
        if (type.equals("sqlite")) return null;
        try {
            String url = switch (type) {
                case "mysql" -> properties.getProperty("url", "jdbc:mysql://127.0.0.1:3306/territory");
                case "postgresql", "postgres" -> properties.getProperty("url", "jdbc:postgresql://127.0.0.1:5432/territory");
                default -> throw new IllegalArgumentException("Unsupported territory database type: " + type);
            };
            Properties credentials = new Properties();
            if (!properties.getProperty("user", "").isBlank())
                credentials.setProperty("user", properties.getProperty("user"));
            if (!properties.getProperty("password", "").isBlank())
                credentials.setProperty("password", properties.getProperty("password"));
            return DriverManager.getConnection(url, credentials);
        } catch (SQLException | IllegalArgumentException ex) {
            System.err.println("[Territory] Remote database unavailable; using local SQLite until it reconnects: " + ex.getMessage());
            return null;
        }
    }

    private void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS territory_claims (
                      claim_key VARCHAR(255) PRIMARY KEY,
                      owner_uuid VARCHAR(36) NOT NULL,
                      owner_name VARCHAR(255) NOT NULL,
                      territory_name VARCHAR(255) NOT NULL
                    )
                    """);
        }
    }

    List<Row> load() {
        return read(local);
    }

    void replace(List<Row> rows) {
        replace(local, rows);
        syncRemote();
    }

    private List<Row> read(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT claim_key, owner_uuid, owner_name, territory_name FROM territory_claims");
             ResultSet result = statement.executeQuery()) {
            List<Row> rows = new ArrayList<>();
            while (result.next()) {
                rows.add(new Row(result.getString(1), UUID.fromString(result.getString(2)),
                        result.getString(3), result.getString(4)));
            }
            return rows;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load territory claims", ex);
        }
    }

    private void replace(Connection connection, List<Row> rows) {
        try {
            connection.setAutoCommit(false);
            try (Statement delete = connection.createStatement()) {
                delete.executeUpdate("DELETE FROM territory_claims");
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO territory_claims (claim_key, owner_uuid, owner_name, territory_name) VALUES (?, ?, ?, ?)")) {
                for (Row row : rows) {
                    insert.setString(1, row.key());
                    insert.setString(2, row.ownerUuid().toString());
                    insert.setString(3, row.ownerName());
                    insert.setString(4, row.territoryName());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
        } catch (SQLException ex) {
            try { connection.rollback(); } catch (SQLException rollback) { ex.addSuppressed(rollback); }
            throw new IllegalStateException("Failed to save territory claims", ex);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ex) {
                throw new IllegalStateException("Failed to reset territory transaction", ex);
            }
        }
    }

    private void syncRemote() {
        try {
            if (remote == null) remote = openRemote(remoteProperties);
            if (remote == null) return;
            try {
                createSchema(remote);
            } catch (SQLException ex) {
                throw new IllegalStateException("Failed to create remote territory schema", ex);
            }
            List<Row> localRows = read(local);
            List<Row> remoteRows = read(remote);
            if (localRows.isEmpty() && !remoteRows.isEmpty()) {
                replace(local, remoteRows);
                localRows = remoteRows;
            }
            replace(remote, localRows);
            remoteAvailable = true;
        } catch (RuntimeException ex) {
            remoteAvailable = false;
            try { if (remote != null) remote.close(); } catch (SQLException ignored) {}
            remote = null;
            System.err.println("[Territory] Remote sync failed; claims remain safely stored in SQLite: " + ex.getMessage());
        }
    }

    boolean isRemoteAvailable() {
        return remoteAvailable;
    }

    void retryRemoteSync() {
        syncRemote();
    }

    @Override
    public void close() {
        try {
            if (remote != null) remote.close();
            local.close();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to close territory database", ex);
        }
    }
}
