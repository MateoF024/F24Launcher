package f24launcher.ipc;

import com.google.gson.Gson;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Canal de eventos en streaming hacia el frontend (WebSocket /events).
 *
 * El backend publica aquí el progreso de descargas/instalación, los logs de la
 * consola del juego y el estado del login. Cada mensaje es un objeto JSON con
 * la forma { "type": "...", "data": ... }.
 */
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final Set<WsContext> clients = ConcurrentHashMap.newKeySet();
    private final Gson gson = new Gson();

    public void register(WsConnectContext ctx) {
        clients.add(ctx);
        log.info("Cliente WS conectado ({} activos)", clients.size());
    }

    public void unregister(WsCloseContext ctx) {
        clients.remove(ctx);
        log.info("Cliente WS desconectado ({} activos)", clients.size());
    }

    /** Difunde un evento tipado a todos los clientes conectados. */
    public void publish(String type, Object data) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", type);
        msg.put("data", data);
        String json = gson.toJson(msg);
        for (WsContext c : clients) {
            try {
                c.send(json);
            } catch (Exception ignored) {
                // cliente caído; se limpiará en onClose
            }
        }
    }

    public int connectedCount() {
        return clients.size();
    }
}
