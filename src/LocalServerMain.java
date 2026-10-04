import java.io.File;

/** Standalone entry point for the recovered dedicated server. No account login. */
public final class LocalServerMain {
    public static void main(String[] args) throws Exception {
        // Establish the vanilla statistics bootstrap before Dispenser initialization.
        // Directly entering Dispenser or Item creates circular static initialization.
        wryd._a();
        gloomyfolken.bundle.common.core.dfaj.init();
        if (noppes.npcs.controllers.PlayerDataController.instance == null) {
            new noppes.npcs.controllers.PlayerDataController();
        }
        final buzu server = new buzu(new File("."));
        server._F();
        final int seconds = Integer.getInteger("reconstruction.stopAfterSeconds", 0);
        if (seconds > 0) {
            Thread stop = new Thread(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(seconds * 1000L);
                        System.out.println("[RECONSTRUCTION] Requesting diagnostic server stop");
                        server._z();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "Reconstruction diagnostic stop");
            stop.setDaemon(true);
            stop.start();
        }
    }
}
