package dev.codeverse.desktop;

import com.badlogic.gdx.backends.lwjgl3.*;

public final class Launcher {
    public static void main(String[] args){
        Lwjgl3ApplicationConfiguration config=new Lwjgl3ApplicationConfiguration();
        config.setTitle("CODEVERSE / The living history of code");config.setWindowedMode(1440,900);
        config.setWindowSizeLimits(1100,720,-1,-1);config.useVsync(true);config.setForegroundFPS(60);
        config.disableAudio(true);
        new Lwjgl3Application(new CodeverseApp(args.length>0?args[0]:null),config);
    }
}
