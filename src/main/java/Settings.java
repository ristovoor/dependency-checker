import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;

public class Settings {
    public Long specTranslationTimeInterval = 7L * 60L * 60L * 24L; // default one week
    public Long cpeTimeInterval = 7L * 60L * 60L * 24L; // default one week
    public Long vulnerabilityTimeInterval = 1L * 60L * 60L * 24L; // default one day
    public URL homeFolder;
    public URL specDirectory;

    public Settings() throws MalformedURLException {
        String homeFolderPath = System.getProperty("user.home") + "/DependencyInfo";
        this.homeFolder = new URL("file:///" + homeFolderPath);
        String specDirectoryPath = homeFolderPath + "/Cocoapods";
        this.specDirectory = new URL("file:///" + specDirectoryPath);
    }
}
class SettingsController implements AutoCloseable {
    Settings settings;
    URL url;
    URL folder;
    boolean changed = false;

    public SettingsController() {
        URL home;
        try {
            String dependencyCheckerFilesPath = System.getProperty("dependency_checker_files_path");
            if (dependencyCheckerFilesPath != null) {
                home = new URL(dependencyCheckerFilesPath);
            } else {
                home = new File(System.getProperty("user.home")).toURI().toURL();
                File dependencyInfoDirectory = new File(home.toURI().resolve("DependencyInfo"));
                if (!dependencyInfoDirectory.exists()) {
                    dependencyInfoDirectory.mkdir();
                }
                home = dependencyInfoDirectory.toURI().toURL();
            }

            LoggerHelper.log(LogLevel.DEBUG, "[*] Initiating settings from: " + home.toString());

            this.folder = home;
            this.url = new URL(this.folder, "settings.json");

            System.setProperty("dependency_checker_files_path", this.folder.toString());

            if (Files.exists(java.nio.file.Paths.get(this.url.toURI()))) {
                try (InputStream inputStream = this.url.openStream()) {
                    // Read data from URL
                    byte[] data = inputStream.readAllBytes();

                    // Decode JSON data
                    Gson gson = new Gson();
                    String jsonData = new String(data);
                    this.settings = gson.fromJson(jsonData, Settings.class);
                } catch (IOException e) {
                    this.settings = new Settings();
                }
            } else {
                this.settings = new Settings();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void close() throws Exception {
        if (changed) {
            save();
        }
    }

    public void save() {
        checkFolder();

        System.setProperty("dependency_checker_files_path", this.folder.toString());

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(this.settings);
        try (Writer writer = new FileWriter(new File(this.url.toURI()))) {
            writer.write(json);
        } catch (IOException | URISyntaxException e) {
            LoggerHelper.log(LogLevel.ERROR, "[!] Could not save settings");
        }
    }

    public void checkFolder() {
        if (!new File(this.folder.getPath()).exists()) {
            try {
                Files.createDirectories(java.nio.file.Paths.get(this.folder.toURI()));
            } catch (IOException | URISyntaxException e) {
                LoggerHelper.log(LogLevel.ERROR, "[!] Could not create folder: " + this.folder.getPath());
            }
        }
    }
}