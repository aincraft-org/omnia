---
name: database-integration
description: Use when adding database persistence to a Paper plugin, configuring HikariCP pooling, connecting to SQLite or MySQL, running async queries, creating schemas or migrations, or handling database errors without blocking the main thread. Triggers include JDBC, HikariCP, SQLite, MySQL, schema migrations, async database access, and connection pooling.
---

# Database Integration (Paper Plugin)

Persist plugin data with a connection pool, async access, and schema migrations — without ever blocking the server's main thread.

Core principle: **all database I/O runs off the main thread; the main thread never waits on a database.**

Pins verified 2026-08-21 against Maven Central and the PaperMC developer docs.

## Pinned versions

| Component | Version |
|---|---|
| HikariCP | **7.0.2** |
| SQLite JDBC driver | **bundled with Paper** (do not shade) |
| Java toolchain | **25** |

Paper bundles the SQLite JDBC driver at runtime. Do not package or shade `org.xerial:sqlite-jdbc` — a duplicate driver conflicts with Paper's. The driver is available from the server, not from the plugin jar.

## Dependencies

```kotlin
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    implementation("com.zaxxer:HikariCP:7.0.2")
}
```

Shade only HikariCP into the plugin jar — Paper does not bundle it. The SQLite driver needs no dependency at all if you use only `DriverManager` with a `jdbc:sqlite:` URL: the driver is discovered at runtime from Paper. Add `compileOnly("org.xerial:sqlite-jdbc:3.53.2.1")` only if your code imports `org.sqlite` types directly (e.g. `SQLiteConfig`); never `implementation` it, because the runtime driver comes from the server. If you target Spigot/Bukkit or another fork, verify whether the driver is bundled there before assuming it is.

## Connection pool

Initialize the pool in `onEnable`, close it in `onDisable`:

```java
public final class ExamplePlugin extends JavaPlugin {
    private HikariDataSource dataSource;

    @Override
    public void onEnable() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + getDataFolder() + "/database.db");
        config.setMaximumPoolSize(1); // SQLite: single writer
        config.setConnectionInitSql("PRAGMA foreign_keys = ON");
        dataSource = new HikariDataSource(config);
    }

    @Override
    public void onDisable() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
```

For MySQL, use a larger pool and prepared-statement caching:

```java
config.setJdbcUrl("jdbc:mysql://localhost:3306/your_db");
config.setUsername("user");
config.setPassword("password");
config.setMaximumPoolSize(10);
config.addDataSourceProperty("cachePrepStmts", "true");
config.addDataSourceProperty("prepStmtCacheSize", "250");
```

SQLite does not support concurrent writers; keep `maximumPoolSize` at 1 to avoid `database is locked` errors. MySQL can use a larger pool sized to server load.

## Async access

Never run queries on the main thread. Use the async scheduler and bring results back with a sync hop only when a Bukkit API call is required:

```java
public CompletableFuture<Optional<PlayerData>> loadPlayerData(UUID playerId) {
    return CompletableFuture.supplyAsync(() -> {
        String sql = "SELECT balance, home FROM player_data WHERE player_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new PlayerData(
                        rs.getDouble("balance"),
                        rs.getString("home")));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load player data", e);
            return Optional.empty();
        }
    });
}
```

Callers that need a Bukkit object (e.g. teleporting a player) must hop back to the main thread:

```java
loadPlayerData(playerId).thenAccept(data -> {
    Bukkit.getScheduler().runTask(plugin, () -> {
        // main-thread work here
    });
});
```

Use `PreparedStatement` for every query — never string-concatenate user input into SQL. Use try-with-resources for `Connection`, `PreparedStatement`, and `ResultSet` so they always close.

## Schema and migrations

Create the schema once at startup, then migrate on version change. Track a schema version in a metadata table:

```java
private static final int SCHEMA_VERSION = 1;

@Override
public void onEnable() {
    // ... pool init ...
    migrate();
}

private void migrate() {
    try (Connection conn = dataSource.getConnection()) {
        conn.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS schema_meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
        """);
        int current = readSchemaVersion(conn);
        if (current < 1) {
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_data (
                    player_id TEXT PRIMARY KEY,
                    balance DOUBLE NOT NULL DEFAULT 0,
                    home TEXT
                )
            """);
            writeSchemaVersion(conn, 1);
        }
        // each later migration bumps SCHEMA_VERSION and applies its own DDL
    } catch (SQLException e) {
        getLogger().log(Level.SEVERE, "Schema migration failed", e);
    }
}
```

Run migrations synchronously at startup before the server accepts players, or as the first async task; never run them lazily from a request path. Keep every migration idempotent (`IF NOT EXISTS` or guarded by the stored version) so a partially applied migration does not corrupt state.

## Verify

```bash
./gradlew build spotlessCheck
./gradlew runServer
```

Start the server, confirm the database file is created, insert a row through a command, restart, and confirm the row survives. Confirm no main-thread stack trace contains your JDBC calls while the server is running under load.

## Common mistakes

| Wrong | Right | Why |
|---|---|---|
| Query on the main thread | Async scheduler + sync hop for Bukkit calls | Main-thread blocking drops TPS |
| New connection per query | HikariCP pool | Pooling avoids connection-open overhead |
| SQLite pool size > 1 | `maximumPoolSize` 1 | SQLite single-writer; concurrency causes `database is locked` |
| String-concatenated SQL | `PreparedStatement` | SQL injection and no query-plan caching |
| Manual `close()` in every path | try-with-resources | Leaks connections and statements |
| Lazy schema creation from request path | Startup migration with stored version | Concurrent first-requests race on DDL |
| Non-idempotent migrations | `IF NOT EXISTS` or version-guarded DDL | Partial migration corrupts state |
| Closing the pool only on unload | Close in `onDisable` | Leaks connections across reloads |
| Shading `sqlite-jdbc` into the plugin jar | Use Paper's bundled driver (`compileOnly` or nothing) | A duplicate driver conflicts with Paper's runtime driver |
| Raw SQL literals in Java files | `.sql` resource files under `src/main/resources/sql/` | Separates queries from logic; easier to review and audit |
