import dev.betavk.platform.Display;
import dev.betavk.render.Backends;
import dev.betavk.render.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.src.Session;
import net.minecraft.src.UnexpectedThrowable;

/** M1 bootstrap: the unpatched, readable jar started by our own code. */
public final class M1Boot extends Minecraft {
    M1Boot(int w, int h) {
        super(null, null, null, w, h, false);
    }

    @Override public void displayUnexpectedThrowable(UnexpectedThrowable t) {
        t.exception.printStackTrace();
    }

    public static void main(String[] args) {
        Backends.install();
        Display.onContextCreated = () -> Gfx.set(Backends.create());
        M1Boot mc = new M1Boot(1280, 720);
        mc.session = new Session("M1", "-");
        mc.minecraftUri = "www.minecraft.net";
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < 5; i++) {
                    Thread.sleep(3000L);
                    System.out.println("[m1] t=" + (i + 1) * 3 + "s screen=" + (mc.currentScreen == null ? null : mc.currentScreen.getClass().getSimpleName()) + " fps=" + mc.debug);
                }
                Display.captureNextFrame(System.getProperty("m1.shot", "/tmp/m1.png"));
                Thread.sleep(3000L);
            } catch (InterruptedException e) {
            }
            System.exit(0);
        });
        t.setDaemon(true);
        t.start();
        try {
            mc.run();
            System.out.println("[m1] run() returned, running=" + mc.running);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
