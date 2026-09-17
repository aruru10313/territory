package de.aruru.territory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TerritoryDatabase implements AutoCloseable {
    record Row(String key, UUID ownerUuid, String ownerName, String territoryName) {}

    private final Connection local;
    private Connection remoteSql;
    private final Properties remoteProperties;
    private final String dbType;
    private volatile boolean remoteAvailable;

    // Asynchronous worker for cloud database operations (prevents blocking Minecraft server tick thread)
    private final ExecutorService cloudExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Territory-CloudDB-Sync");
        t.setDaemon(true);
        return t;
    });

    // HttpClient for Cloud REST APIs (MongoDB Atlas, Supabase REST)
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private TerritoryDatabase(Connection local, Connection remoteSql, Properties remoteProperties, String dbType) {
        this.local = local;
        this.remoteSql = remoteSql;
        this.remoteProperties = remoteProperties;
        this.dbType = dbType;
        this.remoteAvailable = remoteSql != null || "mongodb".equals(dbType);
    }

    static TerritoryDatabase open() {
        try {
            Path directory = Path.of("config", "territory");
            Files.createDirectories(directory);
            Properties properties = loadProperties(directory.resolve("database.properties"));
            String type = properties.getProperty("type", "sqlite").trim().toLowerCase(Locale.ROOT);

            Connection local = DriverManager.getConnection("jdbc:sqlite:" +
                    directory.resolve(properties.getProperty("file", "territory.db")));

            Connection remoteSql = "mongodb".equals(type) ? null : openRemoteSql(properties, type);
            TerritoryDatabase database = new TerritoryDatabase(local, remoteSql, properties, type);

            database.createSchema(local);
            if (database.remoteSql != null) {
                database.createSchema(database.remoteSql);
            }

            // Perform initial sync (if remote has data, pull to local; otherwise push local to remote)
            database.initialSync();
            return database;
        } catch (IOException | SQLException ex) {
            throw new IllegalStateException("Failed to open local territory database", ex);
        }
    }

    private static Properties loadProperties(Path configFile) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(configFile)) {
            try (var reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } else {
            properties.setProperty("type", "sqlite");
            properties.setProperty("file", "territory.db");
            properties.setProperty("# Available types", "sqlite, supabase, postgresql, mysql, mongodb");
            properties.setProperty("# Supabase example url", "jdbc:postgresql://db.xxxx.supabase.co:5432/postgres?sslmode=require");
            properties.setProperty("# MongoDB example endpoint", "https://<region>.data.mongodb-api.com/app/<app-id>/endpoint/data/v1/action");
            try (var writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
                properties.store(writer, "Territory Cloud Database Settings (Supabase / MongoDB / PostgreSQL / MySQL / SQLite)");
            }
        }
        return properties;
    }

    private static Connection openRemoteSql(Properties properties, String type) {
        if ("sqlite".equals(type)) return null;
        try {
            String url = switch (type) {
                case "supabase" -> {
                    String u = properties.getProperty("url", "");
                    if (u.isBlank()) {
                        String host = properties.getProperty("host", "db.xxxx.supabase.co");
                        String port = properties.getProperty("port", "5432");
                        String db = properties.getProperty("database", "postgres");
                        u = "jdbc:postgresql://" + host + ":" + port + "/" + db;
                    }
                    if (!u.contains("sslmode=")) {
                        u += (u.contains("?") ? "&" : "?") + "sslmode=require";
                    }
                    yield u;
                }
                case "postgresql", "postgres" -> {
                    String u = properties.getProperty("url", "jdbc:postgresql://127.0.0.1:5432/territory");
                    if (properties.getProperty("ssl", "false").equalsIgnoreCase("true") && !u.contains("sslmode=")) {
                        u += (u.contains("?") ? "&" : "?") + "sslmode=require";
                    }
                    yield u;
                }
                case "mysql" -> properties.getProperty("url", "jdbc:mysql://127.0.0.1:3306/territory?autoReconnect=true&useSSL=false");
                default -> throw new IllegalArgumentException("Unsupported SQL territory database type: " + type);
            };

            Properties credentials = new Properties();
            if (!properties.getProperty("user", "").isBlank())
                credentials.setProperty("user", properties.getProperty("user"));
            if (!properties.getProperty("password", "").isBlank())
                credentials.setProperty("password", properties.getProperty("password"));

            return DriverManager.getConnection(url, credentials);
        } catch (SQLException | IllegalArgumentException ex) {
            System.err.println("[Territory] Remote SQL database unavailable; claims remain safely stored in local SQLite: " + ex.getMessage());
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
        return readLocal();
    }

    void replace(List<Row> rows) {
        // 1. Immediately write to local SQLite (0.1ms latency, zero server lag)
        replaceLocal(rows);

        // 2. Asynchronously synchronize to Cloud DB in background
        if (!"sqlite".equals(dbType)) {
            cloudExecutor.submit(() -> syncRemoteAsync(new ArrayList<>(rows)));
        }
    }

    private List<Row> readLocal() {
        try (PreparedStatement statement = local.prepareStatement(
                "SELECT claim_key, owner_uuid, owner_name, territory_name FROM territory_claims");
             ResultSet result = statement.executeQuery()) {
            List<Row> rows = new ArrayList<>();
            while (result.next()) {
                rows.add(new Row(result.getString(1), UUID.fromString(result.getString(2)),
                        result.getString(3), result.getString(4)));
            }
            return rows;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load local territory claims", ex);
        }
    }

    private void replaceLocal(List<Row> rows) {
        try {
            local.setAutoCommit(false);
            try (Statement delete = local.createStatement()) {
                delete.executeUpdate("DELETE FROM territory_claims");
            }
            try (PreparedStatement insert = local.prepareStatement(
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
            local.commit();
        } catch (SQLException ex) {
            try { local.rollback(); } catch (SQLException rollback) { ex.addSuppressed(rollback); }
            throw new IllegalStateException("Failed to save local territory claims", ex);
        } finally {
            try { local.setAutoCommit(true); } catch (SQLException ex) {
                throw new IllegalStateException("Failed to reset local territory transaction", ex);
            }
        }
    }

    private void initialSync() {
        if ("sqlite".equals(dbType)) return;

        try {
            if ("mongodb".equals(dbType)) {
                List<Row> mongoRows = readMongoDB();
                List<Row> localRows = readLocal();
                if (localRows.isEmpty() && !mongoRows.isEmpty()) {
                    replaceLocal(mongoRows);
                } else if (!localRows.isEmpty()) {
                    writeMongoDB(localRows);
                }
                remoteAvailable = true;
            } else {
                if (remoteSql == null) remoteSql = openRemoteSql(remoteProperties, dbType);
                if (remoteSql != null) {
                    createSchema(remoteSql);
                    List<Row> localRows = readLocal();
                    List<Row> remoteRows = readRemoteSql(remoteSql);
                    if (localRows.isEmpty() && !remoteRows.isEmpty()) {
                        replaceLocal(remoteRows);
                        localRows = remoteRows;
                    }
                    replaceRemoteSql(remoteSql, localRows);
                    remoteAvailable = true;
                }
            }
        } catch (Exception ex) {
            remoteAvailable = false;
            System.err.println("[Territory] Initial remote sync failed (" + dbType + "); will retry asynchronously: " + ex.getMessage());
        }
    }

    private void syncRemoteAsync(List<Row> rows) {
        try {
            if ("mongodb".equals(dbType)) {
                writeMongoDB(rows);
                remoteAvailable = true;
            } else {
                if (remoteSql == null || remoteSql.isClosed()) {
                    remoteSql = openRemoteSql(remoteProperties, dbType);
                }
                if (remoteSql != null) {
                    createSchema(remoteSql);
                    replaceRemoteSql(remoteSql, rows);
                    remoteAvailable = true;
                }
            }
        } catch (Exception ex) {
            remoteAvailable = false;
            System.err.println("[Territory] Cloud DB async sync failed (" + dbType + "): " + ex.getMessage());
        }
    }

    private List<Row> readRemoteSql(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT claim_key, owner_uuid, owner_name, territory_name FROM territory_claims");
             ResultSet result = statement.executeQuery()) {
            List<Row> rows = new ArrayList<>();
            while (result.next()) {
                rows.add(new Row(result.getString(1), UUID.fromString(result.getString(2)),
                        result.getString(3), result.getString(4)));
            }
            return rows;
        }
    }

    private void replaceRemoteSql(Connection connection, List<Row> rows) throws SQLException {
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
        connection.setAutoCommit(true);
    }

    // --- MongoDB Atlas Data API integration ---
    private List<Row> readMongoDB() throws IOException, InterruptedException {
        String endpoint = remoteProperties.getProperty("mongodb_endpoint", "");
        String apiKey = remoteProperties.getProperty("mongodb_api_key", "");
        String cluster = remoteProperties.getProperty("mongodb_cluster", "Cluster0");
        String database = remoteProperties.getProperty("mongodb_database", "minecraft");
        String collection = remoteProperties.getProperty("mongodb_collection", "territory_claims");

        if (endpoint.isBlank() || apiKey.isBlank()) {
            return List.of();
        }

        String payload = String.format("""
                {
                  "dataSource": "%s",
                  "database": "%s",
                  "collection": "%s",
                  "filter": {}
                }
                """, cluster, database, collection);

        String url = endpoint.endsWith("/") ? endpoint + "find" : endpoint + "/find";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("MongoDB Data API error " + response.statusCode() + ": " + response.body());
        }

        List<Row> list = new ArrayList<>();
        // Lightweight regex parse for documents
        Pattern pattern = Pattern.compile(
                "\"claim_key\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"owner_uuid\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"owner_name\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"territory_name\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(response.body());
        while (matcher.find()) {
            try {
                list.add(new Row(matcher.group(1), UUID.fromString(matcher.group(2)), matcher.group(3), matcher.group(4)));
            } catch (IllegalArgumentException ignored) {}
        }
        return list;
    }

    private void writeMongoDB(List<Row> rows) throws IOException, InterruptedException {
        String endpoint = remoteProperties.getProperty("mongodb_endpoint", "");
        String apiKey = remoteProperties.getProperty("mongodb_api_key", "");
        String cluster = remoteProperties.getProperty("mongodb_cluster", "Cluster0");
        String database = remoteProperties.getProperty("mongodb_database", "minecraft");
        String collection = remoteProperties.getProperty("mongodb_collection", "territory_claims");

        if (endpoint.isBlank() || apiKey.isBlank()) return;

        // 1. deleteMany
        String delUrl = endpoint.endsWith("/") ? endpoint + "deleteMany" : endpoint + "/deleteMany";
        String delPayload = String.format("""
                {"dataSource": "%s", "database": "%s", "collection": "%s", "filter": {}}
                """, cluster, database, collection);
        HttpRequest delReq = HttpRequest.newBuilder()
                .uri(URI.create(delUrl))
                .header("Content-Type", "application/json")
                .header("api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(delPayload, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(10))
                .build();
        httpClient.send(delReq, HttpResponse.BodyHandlers.discarding());

        // 2. insertMany if rows not empty
        if (rows.isEmpty()) return;

        StringBuilder docJson = new StringBuilder();
        docJson.append('[');
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) docJson.append(',');
            Row r = rows.get(i);
            docJson.append(String.format(
                    "{\"claim_key\":\"%s\",\"owner_uuid\":\"%s\",\"owner_name\":\"%s\",\"territory_name\":\"%s\"}",
                    escapeJson(r.key()), r.ownerUuid(), escapeJson(r.ownerName()), escapeJson(r.territoryName())
            ));
        }
        docJson.append(']');

        String insUrl = endpoint.endsWith("/") ? endpoint + "insertMany" : endpoint + "/insertMany";
        String insPayload = String.format("""
                {"dataSource": "%s", "database": "%s", "collection": "%s", "documents": %s}
                """, cluster, database, collection, docJson);

        HttpRequest insReq = HttpRequest.newBuilder()
                .uri(URI.create(insUrl))
                .header("Content-Type", "application/json")
                .header("api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(insPayload, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(10))
                .build();
        httpClient.send(insReq, HttpResponse.BodyHandlers.discarding());
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    boolean isRemoteAvailable() {
        return remoteAvailable;
    }

    void retryRemoteSync() {
        cloudExecutor.submit(() -> syncRemoteAsync(readLocal()));
    }

    @Override
    public void close() {
        cloudExecutor.shutdown();
        try {
            if (!cloudExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                cloudExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cloudExecutor.shutdownNow();
        }

        try {
            if (remoteSql != null && !remoteSql.isClosed()) remoteSql.close();
            if (local != null && !local.isClosed()) local.close();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to close territory database", ex);
        }
    }
}
