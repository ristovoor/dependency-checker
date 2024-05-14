
import java.io.*;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import com.google.gson.*;
import org.javatuples.*;

public class DependencyAnalyser implements AutoCloseable {

    public Translations translations;
    public URL url;
    public URL folder;
    public boolean changed = false;
    public final Settings settings;
    public String specDirectory;
    public boolean onlyDirectDependencies = false;

    public DependencyAnalyser(Settings settings) throws MalformedURLException {
        this.settings = settings;
        this.folder = settings.homeFolder;
        Path translationJsonPath = Paths.get(this.folder.getPath(), "translation.json");
        this.url = translationJsonPath.toUri().toURL();
        try {
            byte[] data = Files.readAllBytes(Paths.get(url.toURI()));
            Gson gson = new Gson();
            String stringData = new String(data);
            Translations decoded = gson.fromJson(stringData, Translations.class);

            JsonObject jsonObject = gson.fromJson(new FileReader(String.valueOf(Paths.get(url.toURI()))), JsonObject.class);
            double epochDate = jsonObject.get("lastUpdated").getAsDouble();

            decoded.date = new Date( (long) (epochDate * 1000)); // Probably wrong conversion
            translations = decoded;
        } catch (IOException | URISyntaxException e) {
            translations = new Translations(new Date(), new HashMap<>());
        }
        this.specDirectory = settings.specDirectory.getPath();
        if (!checkSpecDirectory()) {
            checkoutSpecDirectory();
        }
        if (shouldUpdate()) {
            update();
        }
    }
    @Override
    public void close() {
        if (changed) {
            save();
        }
    }

    public boolean shouldUpdate() {
        LoggerHelper.log(LogLevel.INFO, "[i] Translations last updated: " + translations.date.toString());

        if (settings.specTranslationTimeInterval != null) {
            long timeInterval = settings.specTranslationTimeInterval;
            long elapsedTime = new Date().getTime() - translations.date.getTime();

            if (elapsedTime > timeInterval) {
                LoggerHelper.log(LogLevel.INFO, "[i] Will update spec data");
                return true;
            }
        }

        LoggerHelper.log(LogLevel.INFO, "[i] No update for spec data");
        return false;
    }

    public void update() {
        LoggerHelper.log(LogLevel.INFO, "[*] Updating spec directory ...");
        updateSpecDirectory();
        Map<String, Translation> updatedTranslations = new HashMap<>();

        for (Map.Entry<String, Translation> entry : translations.translations.entrySet()) {
            if (!entry.getValue().noTranslation) {
                updatedTranslations.put(entry.getKey(), entry.getValue());
            }
        }

        translations.date = new Date();
        translations.translations = updatedTranslations;

        changed = true;
    }

    public boolean checkSpecDirectory() {
        Path directory = Paths.get(specDirectory);
        Path specPath = directory.resolve("Specs");

        LoggerHelper.log(LogLevel.DEBUG, "[*] Checking spec path: " + specPath);
        boolean pathExists = Files.exists(specPath);
        LoggerHelper.log(LogLevel.DEBUG, "[i] path exists: " + pathExists);

        return pathExists;
    }

    public void checkoutSpecDirectory() {
        String source = "https://github.com/CocoaPods/Specs.git";
        String directory = specDirectory;
        LoggerHelper.log(LogLevel.DEBUG, "[*] Checking out spec directory into " + directory);

        String[] arguments = {"clone", source, directory};
        String res = Helper.shell("/usr/bin/git", arguments);

        LoggerHelper.log(LogLevel.DEBUG, "[i] Git clone.. " + res);
    }

    public void updateSpecDirectory() {
        LoggerHelper.log(LogLevel.DEBUG, "[*] Updating spec directory ...");
        String directory = specDirectory;
        String gitPath = directory + "/.git";

        String[] arguments = {"--git-dir", gitPath, "--work-tree", directory, "pull"};
        String res = Helper.shell("/usr/bin/git", arguments);

        LoggerHelper.log(LogLevel.DEBUG, "[i] Git pull.. " + res);
    }


    public void save() {
        checkFolder();

        Gson gson = new Gson();
        String json = gson.toJson(translations);

        try (FileOutputStream outputStream = new FileOutputStream(url.getPath())) {
            outputStream.write(json.getBytes());
        } catch (IOException e) {
            LoggerHelper.log(LogLevel.ERROR, "[!] Could not save translations");
            e.printStackTrace();
        }
    }


    public List<Library> analyseApp(String path) throws MalformedURLException, URISyntaxException {
        List<Library> allLibraies = new ArrayList<>();

        List<DependencyFile> dependencyFiles = new ArrayList<>();
        dependencyFiles.add(findPodFile(path));
        dependencyFiles.add(findCarthageFile(path));
        dependencyFiles.add(findSwiftPMFile(path));

        for (DependencyFile dependencyFile : dependencyFiles) {
            if (dependencyFile.isUsed()) {
                if (!dependencyFile.isResolved()){
                    LoggerHelper.log(LogLevel.DEBUG, "[i] Dependency " + dependencyFile.type.value + " defined, but not resolved.");
                    continue;
                }
            }

            if (dependencyFile.isResolved()){
                List<Library> libraries = new ArrayList<>();
                if (dependencyFile.type == DependencyType.CARTHAGE) {
                    libraries = handleCarthageFile(dependencyFile.resolvedFile);
                } else if (dependencyFile.type == DependencyType.COCOAPODS) {
                    libraries = handlePodsFile(dependencyFile.resolvedFile);
                } else if (dependencyFile.type == DependencyType.SWIFT_PM) {
                    libraries = handleSwiftPmFile(dependencyFile.resolvedFile);
                }
                allLibraies.addAll(libraries);
            }
        }

        saveLibraries(path, allLibraies);
        return allLibraies;
    }
    public static void searchInSwiftFiles(String path, String searchString) {
        File directory = new File(path);
        if (!directory.isDirectory()) {
            LoggerHelper.log(LogLevel.ERROR, "Invalid directory path.");
            return;
        }
        search(directory, searchString);
    }

    private static void search(File directory, String searchString) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    search(file, searchString);
                } else if (file.getName().toLowerCase().endsWith(".swift")) {
                    try {
                        if (containsImport(file, searchString)) {
                            searchInFile(file, searchString);
                        }
                    } catch (IOException e) {
                        LoggerHelper.log(LogLevel.ERROR,"Error reading file: " + file.getName());
                    }
                }
            }
        }
    }

    public static boolean containsImport(File file, String searchString) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("import") && line.contains(searchString)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void searchInFile(File file, String searchString) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.contains(searchString)) {
                    int position = line.indexOf(searchString);
                    LoggerHelper.log (LogLevel.INFO, "Found '" + searchString + "' in " + file.getAbsolutePath() + " at line " + lineNumber + ", position " + (position + 1));
                }
            }
        }
    }

    public static List<Library> handleSwiftPmFile(String path) {
        LoggerHelper.log(LogLevel.DEBUG, "[*] Parsing Swift PM resolution file " + path + " ...");
        List<Library> libraries = new ArrayList<>();

        try {
            byte[] data = Files.readAllBytes(Paths.get(path));
            String jsonString = new String(data);
            JsonObject json = new JsonParser().parse(jsonString).getAsJsonObject();

            if (json.has("object")) {
                JsonObject object = json.getAsJsonObject("object");
                if (object.has("pins")) {
                    JsonArray pins = object.getAsJsonArray("pins");
                    for (JsonElement pinElement : pins) {
                        JsonObject pin = pinElement.getAsJsonObject();
                        String name = null;
                        String version = null;
                        String module = pin.get("package").getAsString();
                        String repoURL = pin.get("repositoryURL").getAsString();

                        if (repoURL != null) {
                            String correctName = getNameFrom(repoURL);
                            if (correctName != null) {
                                name = correctName;
                            } else {
                                name = module;
                            }
                        } else {
                            name = module;
                        }

                        JsonObject state = pin.getAsJsonObject("state");
                        if (state != null) {
                            version = state.get("version").getAsString();
                        }

                        Library library = new Library(name != null ? name : "??", version != null ? version : "");
                        library.platform = "swiftpm";
                        library.module = module;
                        LoggerHelper.log(LogLevel.DEBUG, "[i] Found library name: " + name + ", version: " + version);

                        libraries.add(library);
                    }
                }
            }
        } catch (IOException e) {
            LoggerHelper.log(LogLevel.ERROR, "[!] Could not read swiftPM file " + path);
        }

        return libraries;
    }

    public static String getNameFrom(String url) {
        String value = url.replace(".git", "");
        String[] components = value.split("/");
        if (components.length < 2) {
            return null;
        }

        int count = components.length;
        return components[count - 2] + "/" + components[count - 1];
    }

    public List<Library> handlePodsFile(String path) {
        LoggerHelper.log(LogLevel.DEBUG, "[*] Parsing CocoaPods resolution file " + path + " ...");
        List<Library> libraries = new ArrayList<>();
        List<String> declaredPods = new ArrayList<>();

        try {
            String data = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            String[] lines = data.split("\\r?\\n");
            LoggerHelper.log(LogLevel.DEBUG, "[i] Lines: " + String.join(", ", lines));

            String charactersBeforeDash = "";
            for (String line : lines) {
                String changedLine = line.trim();

                if (changedLine.startsWith("PODS:")) {
                    continue;
                }

                if (changedLine.startsWith("-")) {
                    charactersBeforeDash = line.split("-")[0];
                    break;
                }
                LoggerHelper.log(LogLevel.DEBUG, "[i] Characters before dash: " + charactersBeforeDash);
            }

            boolean reachedDependencies = false;

            for (String fixedLine : lines) {
                String line = fixedLine;
                if (line.startsWith("DEPENDENCIES:")) {
                    reachedDependencies = true;
                    continue;
                }

                if (reachedDependencies) {
                    if (line.startsWith(charactersBeforeDash + "- ")) {
                        line = line.replace(charactersBeforeDash + "- ", "");
                        String[] components = line.trim().split("\\s+");
                        String name = components[0].replace("\"", "").toLowerCase();
                        declaredPods.add(name);
                    }

                    if (line.trim().isEmpty()) {
                        break;
                    }

                    if (line.startsWith("SPEC REPOS:")) {
                        break;
                    }
                }
            }

            LoggerHelper.log(LogLevel.DEBUG, "[i] Declared pods: " + declaredPods);

            for (String line : lines) {
                if (line.startsWith("DEPENDENCIES:")) {
                    break;
                }

                if (line.startsWith("PODS:")) {
                    continue;
                }

                if (line.startsWith(charactersBeforeDash + "- ")) {
                    line = line.toLowerCase();
                    line = line.replace(charactersBeforeDash + "- ", "");
                    String[] components = line.trim().split("\\s+");

                    if (components.length < 2) {
                        continue;
                    }

                    String name = components[0].replace("\"", "").toLowerCase().replace("'", "");
                    String version = components[1].trim().replace(":", "").replace("\"", "");
                    version = version.substring(1, version.length() - 1);
                    boolean direct = declaredPods.contains(name);

                    if (!direct && onlyDirectDependencies) {
                        continue;
                    }

                    String subspec = null;
                    if (name.contains("/")) {
                        String[] nameComponents = name.split("/");
                        name = nameComponents[0];
                        subspec = String.join("/", nameComponents[1]);
                    }

                    String oldName = name;
                    String module = null;
                    Tuple translation = translateLibraryVersion(name, version);
                    if (translation != null) {
                        name = (String) translation.getValue(0);
                        if (translation.getValue(2) != null) {
                            version = (String) translation.getValue(2);
                        }
                        module = (String) translation.getValue(1);
                    }

                    Library library = new Library(name, version);
                    library.directDependency = direct;
                    library.subtarget = subspec;
                    library.module = (module != null) ? module : oldName;
                    library.platform = "cocoapods";

                    libraries.add(library);

                    LoggerHelper.log(LogLevel.DEBUG, "[*] Saving library, name: " + library.name + ", version: " + version);
                }
            }
        } catch (IOException e) {
            System.out.println(e);
            LoggerHelper.log(LogLevel.ERROR, "[!] Could not read pods file " + path);
        }

        return libraries;
    }
    public static List<Library> handleCarthageFile(String path) {
        LoggerHelper.log(LogLevel.DEBUG, "[*] Parsing Carthage resolution file " + path);
        List<Library> libraries = new ArrayList<>();
        try {
            String data = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            String[] lines = data.split("\\r?\\n");

            for (String line : lines) {
                String[] components = line.split("\\s+");
                LoggerHelper.log(LogLevel.DEBUG, "[i] Dependency components: " + Arrays.toString(components));
                if (components.length != 3) {
                    break;
                }

                String[] nameComponents = components[1].split("/");
                String name;
                if (nameComponents.length >= 2) {
                    name = nameComponents[nameComponents.length - 2] + "/" + nameComponents[nameComponents.length - 1];
                } else {
                    name = components[1];
                }

                name = name.replace("\"", "");

                if (name.endsWith(".git")) {
                    name = name.replace(".git", ""); // sometimes .git remains behind name, must be removed
                }

                if (name.startsWith("git@github.com:")) {
                    name = name.replace("git@github.com:", ""); // for github projects, transform to regular username/projectname format
                }

                if (name.startsWith("git@bitbucket.org:")) { // for bitbucket projects keep bitbucket part to distinguish it
                    name = name.replace("git@", "").replace(":", "/");
                }

                String version = components[2].replace("\"", "");

                Library library = new Library(name, version);
                library.platform = "carthage";
                LoggerHelper.log(LogLevel.DEBUG, "Found library: " + name + ", version: " + version);

                libraries.add(library);
            }
        } catch (IOException e) {
            LoggerHelper.log(LogLevel.ERROR, "[!] Could not read carthage file " + path);
        }

        return libraries;
    }

    public void saveLibraries(String path, List<Library> libraries) throws URISyntaxException {
        checkFolder();

        Path projectsPath = Paths.get(folder.toURI()).resolve("project.json");
        Projects projects = null;

        try {
            byte[] data = Files.readAllBytes(projectsPath);
            Gson gson = new Gson();
            projects = gson.fromJson(new String(data), Projects.class);
        } catch (IOException e) {
            // File does not exist or cannot be read, ignore
        }

        if (projects == null) {
            projects = new Projects();
        }

        projects.getUsedLibraries().put(path, libraries);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(projects);

        try {
            Files.write(projectsPath, json.getBytes());
        } catch (IOException e) {
            LoggerHelper.log(LogLevel.ERROR, "[!] Could not save projects");
        }
    }
    private void checkFolder() {
        File folderFile;
        try {
            folderFile = new File(folder.toURI());
        } catch (Exception e) {
            LoggerHelper.log(LogLevel.ERROR, "[!] Invalid folder URL: " + folder.toString());
            return;
        }

        if (!folderFile.exists()) {
            try {
                if (!folderFile.mkdirs()) {
                    LoggerHelper.log(LogLevel.ERROR, "[!] Could not create folder: " + folder.toString());
                }
            } catch (Exception e) {
                LoggerHelper.log(LogLevel.ERROR, "[!] Could not create folder: " + folder.toString());
            }
        }
    }


    private DependencyFile findSwiftPMFile(String homePath) {
        File url = new File(homePath);
        String definitionPath = new File(url, "Package.swift").getPath();
        String resolvedPath = new File(url, "Package.resolved").getPath();

        File fileManager = new File("");

        if (!new File(definitionPath).exists()) {
            definitionPath = null;
        }

        if (!new File(resolvedPath).exists()) {
            resolvedPath = null;

            String resolvedInProject = findPackageResolved(homePath);
            if (resolvedInProject != null) {
                resolvedPath = resolvedInProject;
                LoggerHelper.log(LogLevel.INFO, "Found path: " + resolvedPath);
            }
        }

        return new DependencyFile(DependencyType.SWIFT_PM, definitionPath, resolvedPath, definitionPath);
    }

    private String findPackageResolved(String homePath) {
        LoggerHelper.log(LogLevel.INFO, "Try to find resolved path in home: " + homePath);

        File folder = new File(homePath);
        File[] listOfFiles = folder.listFiles();

        if (listOfFiles != null) {
            for (File file : listOfFiles) {
                if (file.isDirectory() && file.getName().endsWith(".xcodeproj")) {
                    String composedPath = file.getPath() + "/project.xcworkspace/xcshareddata/swiftpm/Package.resolved";

                    File resolvedFile = new File(composedPath);
                    if (resolvedFile.exists()) {
                        return composedPath;
                    }
                }
            }
        }
        return null;
    }

    private DependencyFile findCarthageFile(String homePath) {
        File url = new File(homePath);
        String definitionPath = new File(url, "Cartfile").getPath();
        String resolvedPath = new File(url, "Cartfile.resolved").getPath();

        if (!new File(definitionPath).exists()) {
            definitionPath = null;
        }

        if (!new File(resolvedPath).exists()) {
            resolvedPath = null;
        }

        return new DependencyFile(DependencyType.CARTHAGE, definitionPath, resolvedPath, definitionPath);
    }

    public Tuple translateLibraryVersion(String name, String version) {
        LoggerHelper.log(LogLevel.DEBUG, "[*] Translating library name: " + name + ", version: " + version + " ...");

        String specSubPath = this.specDirectory + "/Specs";

        Translation translation = this.translations.translations.get(name);
        if (translation != null) {
            if (translation.noTranslation) {
                return null;
            }

            if (translation.translatedVersions.containsKey(version)) {
                if (translation.libraryName != null) {
                    return new Triplet(translation.libraryName, translation.moduleName, translation.translatedVersions.get(version));
                } else {
                    return null;
                }
            } else {
                if (translation.specFolderPath != null) {
                    String podSpecPath = null;
                    try (Stream<Path> paths = Files.walk(Paths.get(translation.specFolderPath))) {
                        Optional<Path> optionalPath = paths.filter(path -> path.endsWith("podspec.json")).findFirst();
                        if (optionalPath.isPresent()) {
                            podSpecPath = optionalPath.get().toString();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    if (podSpecPath != null) {
                        String finalPodSpecPath = podSpecPath;
                        Optional<String> filename = null;
                        try (Stream<Path> paths = Files.walk(Paths.get(translation.specFolderPath))) {
                            filename = paths.filter(path -> path.toString().toLowerCase().startsWith(version.toLowerCase()) && path.toString().endsWith("podspec.json")).map(Path::toString).findFirst();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                        if (filename.isPresent()) {
                            LoggerHelper.log(LogLevel.DEBUG, "[*] Fetching info from file ...");

                            Map<String, String> values = findValuesInPodspecFile(new String[]{"tag", "module_name", "git"}, finalPodSpecPath);

                            String tag = values.get("tag");
                            String module = values.get("module_name");
                            String gitPath = values.get("git");
                            LoggerHelper.log(LogLevel.DEBUG, "[i] Found gitPath: " + gitPath);
                            LoggerHelper.log(LogLevel.DEBUG, "[i] Found module: " + module);
                            LoggerHelper.log(LogLevel.DEBUG, "[i] Found tag: " + tag);

                            String libraryName = getNameFromGitPath(gitPath);

                            if (tag != null && !tag.isEmpty()) {
                                translation.translatedVersions.put(version, tag);
                            }

                            if (libraryName != null) {
                                libraryName = libraryName.trim();
                                libraryName = libraryName.replace("\"", "");
                                libraryName = libraryName.replace(",", "");

                                translation.libraryName = libraryName;
                                translation.moduleName = module;
                                this.translations.translations.put(name, translation);
                                this.changed = true;

                                if (libraryName != null) {
                                    LoggerHelper.log(LogLevel.DEBUG, "[i] Translation with name and version.");
                                    return new Triplet<>(libraryName, module, translation.translatedVersions.get(version));
                                } else {
                                    return null;
                                }
                            }
                        }
                    }
                } else {
                    LoggerHelper.log(LogLevel.DEBUG, "[i] Null translation from dictionary");
                    return null; // it was a null translation for speed purposes
                }

                if (translation.libraryName != null) {
                    LoggerHelper.log(LogLevel.DEBUG, "[i] Translation with no version from dictionary.");
                    return new Triplet<>(translation.libraryName, translation.moduleName, null);
                }
            }
        } else {
            LoggerHelper.log(LogLevel.DEBUG, "[*] Analysing spec sub path: " + specSubPath);
            // find library in specs
            try (Stream<Path> paths = Files.walk(Paths.get(specSubPath))) {
                Optional<Path> optionalPath = paths.filter(path -> path.toString().toLowerCase().endsWith("/" + name.toLowerCase())).findFirst();
                if (optionalPath.isPresent()) {
                    String filename = optionalPath.get().toString();
                    LoggerHelper.log(LogLevel.DEBUG, "[i] Found file: " + filename);

                    translation = new Translation(name);
                    translation.specFolderPath = filename;
                    translations.translations.put(name, translation);
                    this.changed = true;

                    LoggerHelper.log(LogLevel.DEBUG, "[*] Saving translation with podspec sub path and running translate again ...");
                    return translateLibraryVersion(name, version);
                }

                return null;
            } catch (IOException e) {
                e.printStackTrace();
            }

            translation = new Translation(name);
            translation.noTranslation = true;
            this.translations.translations.put(name, translation);
            this.changed = true;
            // add null translation to speed up project analysis for projects that have many dependencies that cannot be found in cocoapods
        }

        translation = new Translation(name);
        translation.noTranslation = true;
        this.translations.translations.put(name, translation);
        this.changed = true;

        LoggerHelper.log(LogLevel.DEBUG, "[i] No translation found, saving and returning null.");
        return null;
    }

    public String getNameFromGitPath(String path) {
        String libraryName = null;
        if (path.contains(".com")) {
            libraryName = path.substring(path.lastIndexOf(".com") + 4, path.lastIndexOf(".git"));
        } else if (path.contains(".org")) {
            libraryName = path.substring(path.lastIndexOf(".org") + 4, path.lastIndexOf(".git"));
        }

        if (libraryName != null) {
            libraryName = libraryName.toLowerCase();
            libraryName = libraryName.substring(1).trim().replace("\"", "").replace(",", "");
        }

        return libraryName;
    }

    public Map<String, String> findValuesInPodspecFile(String[] keys, String path) {
        Map<String, String> dictionary = new HashMap<>();

        try {
            String fileContents = new String(Files.readAllBytes(Paths.get(path)));
            String[] lines = fileContents.split("\\r?\\n");

            for (String key : keys) {
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("\"" + key + "\": ")) {
                        String value = line.trim();
                        value = value.replace("\"" + key + "\": ", "");
                        value = value.replace("\"", "");
                        value = value.replace(",", "");

                        dictionary.put(key, value);
                        break;
                    }
                }
            }
        } catch (IOException e) {
            LoggerHelper.log(LogLevel.ERROR, "[!] Could not read spec file at: " + path);
        }

        return dictionary;
    }

    public DependencyFile findPodFile(String homePath) throws MalformedURLException {
        File url = new File(homePath);
        String definitionPath = new File(url, "Podfile").getPath();
        String resolvedPath = new File(url, "Podfile.lock").getPath();

        if (!new File(definitionPath).exists()) {
            definitionPath = null;
        }

        if (!new File(resolvedPath).exists()) {
            resolvedPath = null;
        }

        return new DependencyFile(DependencyType.COCOAPODS, definitionPath, resolvedPath, definitionPath);
    }
}
class Library {
    public String name;
    public String subtarget;
    public String versionString;
    public Boolean directDependency;
    public String module;
    public String platform;

    public Library(String name, String versionString) {
        this.name = name.toLowerCase();
        this.versionString = versionString;
    }
}
enum DependencyType {
    COCOAPODS("cocoapods"),
    CARTHAGE("carthage"),
    SWIFT_PM("swiftPM");

    public final String value;

    DependencyType(String value) {
        this.value = value;
    }
}
class DependencyFile {
    public DependencyType type;
    public String file;
    public String resolvedFile;
    private String definitionFile;

    public DependencyFile(DependencyType type, String file, String resolvedFile, String definitionFile) {
        this.type = type;
        this.file = file;
        this.resolvedFile = resolvedFile;
        this.definitionFile = definitionFile;
    }

    public boolean isUsed() {
        return file != null;
    }

    public boolean isResolved() {
        return resolvedFile != null;
    }
}
class Translations {
    public Date date;
    public Map<String, Translation> translations;

    public Translations(Date lastUpdated, Map<String, Translation> translations) {
        this.date = lastUpdated;
        this.translations = translations;
    }

        /*
        public Date getDate() {
            return new Date((long) (lastUpdatedEpoch * 1000L));
        }

        public Translations(double epochTime, Map<String, Translation> translations) {
            this.lastUpdated = new Date((long) epochTime);
            this.translations = translations;
        }

         */

}
class Translation {
    public String podspecName;
    public String gitPath;
    public String libraryName;
    public String moduleName;
    public String specFolderPath;
    public Map<String, String> translatedVersions = new HashMap<>();
    public boolean noTranslation = false;

    public Translation(String podspecName) {
        this.podspecName = podspecName;
    }
}
class Projects {
    private Map<String, List<Library>> usedLibraries = new HashMap<>();

    public Projects() {
        // Default constructor
    }

    public Map<String, List<Library>> getUsedLibraries() {
        return usedLibraries;
    }

    public void setUsedLibraries(Map<String, List<Library>> usedLibraries) {
        this.usedLibraries = usedLibraries;
    }
}

