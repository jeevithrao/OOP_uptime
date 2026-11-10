import console.ConsoleMenu;
import engine.MonitoringEngine;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Entry point. Deliberately thin: pick the engine, start the menu. No logic lives here.
 * Run with "--seed 42" for demo mode, where randomness is reproducible.
 */
public class Main {

    public static void main(String[] args) {
        MonitoringEngine engine = createEngine(args);
        new ConsoleMenu(engine, new BufferedReader(new InputStreamReader(System.in)), System.out).run();
    }

    private static MonitoringEngine createEngine(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--seed")) {
                try {
                    long seed = Long.parseLong(args[i + 1]);
                    System.out.println("Demo mode: random seed " + seed + " (runs are reproducible)\n");
                    return MonitoringEngine.initialize(seed);
                } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                    System.out.println("Usage: java -cp out Main [--seed <whole number>]");
                    System.exit(1);
                }
            }
        }
        return MonitoringEngine.getInstance();
    }
}
