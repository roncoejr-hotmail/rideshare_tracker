package org.miosys.transport;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import org.miosys.transport.auth.Door;
import org.miosys.transport.auth.KeyRepository;
import org.miosys.transport.auth.TenantRepository;
import org.miosys.transport.config.Config;
import org.miosys.transport.db.Database;
import org.miosys.transport.db.Migrations;
import org.miosys.transport.http.ApiHandler;
import org.miosys.transport.http.Router;
import org.miosys.transport.http.Routes;
import org.miosys.transport.store.KeySelfService;
import org.miosys.transport.store.SqliteKeySelfService;
import org.miosys.transport.store.SqliteTenantAdmin;
import org.miosys.transport.store.SqliteTransportStore;
import org.miosys.transport.store.TenantAdmin;
import org.miosys.transport.store.TransportStore;

/** Entry point: config, migrations, then the HTTP server on virtual threads. */
public final class App {

    private static final System.Logger LOG = System.getLogger("transport");

    private App() {}

    public static void main(String[] args) throws Exception {
        Config config = Config.read();
        Database db = new Database(config.dbPath());
        int version;
        try {
            version = Migrations.run(db);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "startup failed during migrations", e);
            System.exit(1);
            return;
        }
        TransportStore store = new SqliteTransportStore(db);
        TenantAdmin admin = new SqliteTenantAdmin(db);
        KeySelfService self = new SqliteKeySelfService(db);
        Router router = new Router();
        Routes.register(router, store, admin, self);
        Door door = new Door(new KeyRepository(db), new TenantRepository(db));
        HttpServer server = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", new ApiHandler(router, door));
        server.start();
        LOG.log(
                System.Logger.Level.INFO,
                "listening on " + config.bind() + ":" + config.port() + " (schema v" + version + ")");
    }
}
