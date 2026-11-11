package com.gathin.tvhome.vpn;

import android.content.Context;

import org.amnezia.awg.backend.Backend;
import org.amnezia.awg.backend.GoBackend;
import org.amnezia.awg.backend.Tunnel;
import org.amnezia.awg.config.Config;

/**
 * The launcher's own VPN: AmneziaWG (WireGuard with DPI obfuscation) running inside this app.
 *
 * The backend and its {@code libwg-go.so} come from the user's SlimVPN project (MIT / Apache-2.0 AmneziaWG),
 * so pressing the VPN pill on the universal Home raises our own tunnel instead of opening another app.
 * Config profiles are plain AmneziaWG {@code .conf} files in the app's external files dir
 * ({@code Android/data/<package>/files/vpn/}); nothing secret is compiled into the APK.
 */
public final class VpnController {

    public interface Callback {
        void done(boolean ok, String error);
    }

    /** Told on a background thread when the tunnel goes up or down, so the pill can repaint. */
    public interface Listener {
        void onState(boolean connected);
    }

    private static Backend backend;
    private static Tunnel tunnel;
    private static boolean connected;
    private static Listener listener;

    private VpnController() {
    }

    public static synchronized Backend backend(Context context) {
        if (backend == null) backend = new GoBackend(context.getApplicationContext());
        return backend;
    }

    public static synchronized boolean isConnected() {
        return connected;
    }

    public static synchronized void setListener(Listener l) {
        listener = l;
    }

    private static void notifyState(boolean up) {
        final Listener l;
        synchronized (VpnController.class) {
            connected = up;
            l = listener;
        }
        if (l != null) l.onState(up);
    }

    public static synchronized void connect(final Context context, final Config config, final Callback cb) {
        if (connected) {
            if (cb != null) cb.done(true, null);
            return;
        }
        if (config == null) {
            if (cb != null) cb.done(false, "Нет профиля VPN");
            return;
        }
        final Backend b;
        try {
            b = backend(context);
        } catch (Throwable t) {
            // e.g. the TV has no matching ABI for libwg-go.so: do not crash, just report it
            if (cb != null) cb.done(false, "VPN-движок недоступен на этом телевизоре");
            return;
        }
        new Thread(() -> {
            String error = null;
            try {
                if (tunnel == null) {
                    tunnel = new Tunnel() {
                        @Override
                        public String getName() {
                            return "tvhome";
                        }

                        @Override
                        public void onStateChange(State state) {
                            notifyState(state == State.UP);
                        }
                    };
                }
                b.setState(tunnel, Tunnel.State.UP, config);
                notifyState(true);
            } catch (Throwable t) {
                error = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
            }
            if (cb != null) cb.done(error == null, error);
        }, "tvhome-vpn-connect").start();
    }

    public static synchronized void disconnect(final Callback cb) {
        final Tunnel t = tunnel;
        if (t == null) {
            notifyState(false);
            if (cb != null) cb.done(true, null);
            return;
        }
        final Backend b = backend;
        new Thread(() -> {
            String error = null;
            try {
                if (b != null) b.setState(t, Tunnel.State.DOWN, null);
            } catch (Throwable th) {
                error = String.valueOf(th.getMessage());
            }
            notifyState(false);
            if (cb != null) cb.done(error == null, error);
        }, "tvhome-vpn-disconnect").start();
    }
}
