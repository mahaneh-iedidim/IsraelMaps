package mp;

import javax.microedition.lcdui.Display;
import javax.microedition.midlet.MIDlet;

public class Maps extends MIDlet {
    Ui ui;

    protected void startApp() {
        if (ui == null) ui = new Ui(this);
        Display.getDisplay(this).setCurrent(ui);
    }

    protected void pauseApp() {
        if (ui != null) ui.save();
    }

    protected void destroyApp(boolean unconditional) {
        if (ui != null) {
            ui.save();
            ui.close();
        }
    }

    void quit() {
        destroyApp(false);
        notifyDestroyed();
    }
}
