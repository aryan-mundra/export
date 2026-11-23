package com.bnpp.export.service.impl;

import com.bnpp.export.ExportApplication;
import com.bnpp.export.config.DatabaseSettings;
import com.bnpp.export.config.MockProperties;
import com.bnpp.export.dto.DatabaseSwitchRequest;
import com.bnpp.export.dto.DatabaseSwitchResponse;
import com.bnpp.export.dto.SystemInfoResponse;
import com.bnpp.export.exception.BadRequestException;
import com.bnpp.export.exception.ConflictException;
import com.bnpp.export.repository.ExportJobRepository;
import com.bnpp.export.security.CurrentUser;
import com.bnpp.export.service.SystemService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/** Implementation of {@link SystemService}. */
@Service
public class SystemServiceImpl implements SystemService {

    private static final Logger log = LoggerFactory.getLogger(SystemServiceImpl.class);

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.\\-]{1,253}");
    private static final Pattern DATABASE = Pattern.compile("[A-Za-z0-9_\\-]{1,63}");
    /** Tables ver-export-schema.sql creates; the module cannot start on PostgreSQL without them. */
    private static final List<String> REQUIRED_TABLES = List.of("ver_export_subscriber", "ver_export_dump_import");

    private final MockProperties mockProperties;
    private final ExportJobRepository jobs;
    private final boolean switchEnabled;

    public SystemServiceImpl(MockProperties mockProperties,
                             ExportJobRepository jobs,
                             @Value("${export.database-switch.enabled:true}") boolean switchEnabled) {
        this.mockProperties = mockProperties;
        this.jobs = jobs;
        this.switchEnabled = switchEnabled;
    }

    @Override
    public SystemInfoResponse info() {
        boolean mock = mockProperties.isEnabled();
        DatabaseSettings saved = DatabaseSettings.load();
        return new SystemInfoResponse(mock, mock ? DatabaseSettings.H2 : DatabaseSettings.POSTGRES, mock ? "H2" : "PostgreSQL",
                switchEnabled && ExportApplication.canRestart(), saved.host(), saved.port(), saved.database(), saved.username(),
                StringUtils.isNotEmpty(saved.password()), mock ? saved.lastError() : null);
    }

    @Override
    public DatabaseSwitchResponse switchDatabase(DatabaseSwitchRequest request) {
        if (!switchEnabled || !ExportApplication.canRestart()) {
            throw new BadRequestException("The database cannot be switched here: the module was started with a fixed profile. "
                    + "Start it without --spring.profiles.active to use the switch.");
        }
        if (jobs.countByStatusIn(List.of("PENDING", "IN_PROGRESS")) > 0) {
            throw new ConflictException("An export is running. Switch the database when it has finished.");
        }
        String mode = request.mode().trim().toLowerCase(Locale.ROOT);
        DatabaseSettings saved = DatabaseSettings.load();
        DatabaseSettings chosen;
        if (DatabaseSettings.H2.equals(mode)) {
            chosen = saved.withMode(DatabaseSettings.H2, null);
        } else if (DatabaseSettings.POSTGRES.equals(mode)) {
            chosen = postgres(request, saved);
            check(chosen);
        } else {
            throw new BadRequestException("Choose h2 or postgres");
        }
        chosen.save();
        log.info("{} switched the database to {}{}; restarting", CurrentUser.actor(), chosen.mode(),
                DatabaseSettings.POSTGRES.equals(chosen.mode()) ? " (" + chosen.jdbcUrl() + ", user " + chosen.username() + ")" : "");
        ExportApplication.restart();
        String label = DatabaseSettings.POSTGRES.equals(chosen.mode()) ? "PostgreSQL" : "H2";
        return new DatabaseSwitchResponse(true, chosen.mode(), "Restarting on " + label + ". Sign in again in a few seconds.");
    }

    /** The PostgreSQL connection from the dialog; an empty password keeps the saved one. */
    private static DatabaseSettings postgres(DatabaseSwitchRequest request, DatabaseSettings saved) {
        String host = StringUtils.trimToEmpty(request.host());
        String database = StringUtils.trimToEmpty(request.database());
        String username = StringUtils.trimToEmpty(request.username());
        int port = request.port() == null ? 5432 : request.port();
        if (!HOST.matcher(host).matches()) {
            throw new BadRequestException("Enter the server's host name, for example localhost");
        }
        if (port < 1 || port > 65535) {
            throw new BadRequestException("Enter a port between 1 and 65535, usually 5432");
        }
        if (!DATABASE.matcher(database).matches()) {
            throw new BadRequestException("Enter the database name, for example shinedata (letters, digits, _ and -)");
        }
        if (username.isEmpty()) {
            throw new BadRequestException("Enter the user name, for example postgres");
        }
        String password = StringUtils.isEmpty(request.password()) && username.equals(saved.username())
                && host.equals(saved.host()) && database.equals(saved.database()) ? saved.password() : request.password();
        return new DatabaseSettings(DatabaseSettings.POSTGRES, host, port, database, username, password == null ? "" : password, null);
    }

    /** Connects with the chosen settings and checks the module's tables are there, so the restart can succeed. */
    private static void check(DatabaseSettings settings) {
        Properties connection = new Properties();
        connection.setProperty("user", settings.username());
        connection.setProperty("password", settings.password());
        connection.setProperty("connectTimeout", "5");
        connection.setProperty("loginTimeout", "5");
        try (Connection db = DriverManager.getConnection(settings.jdbcUrl(), connection);
             PreparedStatement query = db.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_name IN (?, ?)")) {
            query.setString(1, REQUIRED_TABLES.get(0));
            query.setString(2, REQUIRED_TABLES.get(1));
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                if (rows.getInt(1) < REQUIRED_TABLES.size()) {
                    throw new BadRequestException("The database " + settings.database() + " does not have the module's ver_export_ tables yet. "
                            + "In pgAdmin, open a Query Tool on " + settings.database() + ", open db/postgres/ver-export-schema.sql and run it (F5).");
                }
            }
        } catch (SQLException ex) {
            throw new BadRequestException(explain(ex, settings));
        }
    }

    private static String explain(SQLException ex, DatabaseSettings settings) {
        String message = StringUtils.defaultString(ex.getMessage());
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("password authentication failed")) {
            return "Wrong password for user " + settings.username() + ".";
        }
        if (lower.contains("does not exist") && lower.contains("database")) {
            return "There is no database " + settings.database() + " on this server. Create it in pgAdmin, or check the name.";
        }
        if (lower.contains("does not exist") && lower.contains("role")) {
            return "There is no user " + settings.username() + " on this server.";
        }
        if (lower.contains("refused") || lower.contains("timed out") || lower.contains("unknownhost") || lower.contains("unknown host")) {
            return "No PostgreSQL answers on " + settings.host() + ":" + settings.port() + ". Check the host, the port and that the server is running.";
        }
        return "Could not connect to PostgreSQL: " + StringUtils.abbreviate(message, 300);
    }
}
