package dev.codeverse.desktop;

import com.badlogic.gdx.backends.lwjgl3.*;

public final class Launcher {
    public static void main(String[] args){
        Lwjgl3ApplicationConfiguration config=new Lwjgl3ApplicationConfiguration();
        config.setTitle("CODEVERSE / The living history of code");config.setWindowedMode(1440,900);
        config.setWindowSizeLimits(1100,720,-1,-1);config.useVsync(true);config.setForegroundFPS(60);
        config.disableAudio(true);
        if(args.length>0&&args[0].equals("--benchmark")){config.useVsync(false);config.setForegroundFPS(0);new Lwjgl3Application(new RenderBenchmark(Integer.parseInt(args[1]),java.nio.file.Path.of(args[2])),config);return;}
        CodeverseApp app=new CodeverseApp(args.length>0?args[0]:null);
        config.setWindowListener(new Lwjgl3WindowAdapter(){@Override public void filesDropped(String[] files){app.dropped(files);}});
        new Lwjgl3Application(app,config);
    }
}
