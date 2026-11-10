import console.ConsoleMenu;
import engine.MonitoringEngine;
import exception.StorageException;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Entry point. Deliberately thin: pick the engine, load saved data, start the menu. No logic lives here.
 * Run with "--seed 42" for demo mode: reproducible randomness, a clean start, and a separate
 * data folder so demos never touch real saved data.
 */
public class Main {

    public static void main(String[] args) {
        Long seed = parseSeed(args);
        MonitoringEngine engine;
        if (seed == null) {
            engine = MonitoringEngine.getInstance();
            loadSavedData(engine);
        } else {
            engine = MonitoringEngine.initialize(seed);
            System.out.println("Demo mode: random seed " + seed + ", starting empty (runs are reproducible)\n");
        }
        new ConsoleMenu(engine, new BufferedReader(new InputStreamReader(System.in)), System.out).run();
    }

    /** @return the seed, or null when demo mode was not requested */
    private static Long parseSeed(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--seed")) {
                try {
                    return Long.parseLong(args[i + 1]);
                } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                    System.out.println("Usage: java -cp out Main [--seed <whole number>]");
                    System.exit(1);
                }
            }
        }
        return null;
    }

    /** A corrupt data file stops the program instead of starting empty, because the first save would overwrite it. */
    private static void loadSavedData(MonitoringEngine engine) {
        try {
            engine.load();
            if (!engine.getServices().isEmpty()) {
                System.out.println("Loaded " + engine.getServices().size() + " service(s) and "
                        + engine.getIncidentHistory().size() + " incident(s) from saved data.\n");
            }
        } catch (StorageException e) {
            System.out.println("Could not load saved data: " + e.getMessage());
            System.out.println("Nothing was changed. Fix or delete the files in the '"
                    + MonitoringEngine.DEFAULT_DATA_DIR + "' folder, then start again.");
            System.exit(1);
        }
    }
}
