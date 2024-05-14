import org.javatuples.Pair;
import org.javatuples.Tuple;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

@Command(name = "CheckDependancyCLI",
        subcommands = {Analyse.class, ToolSettings.class, CommandLine.HelpCommand.class})
public class main {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new main()).execute(args);
        System.exit(exitCode);
    }
}

@Command(name = "analyse")
class Analyse implements Runnable {

    @CommandLine.Option(names = {"-path"}, description = "Path of the project to be analysed, " +
            "if not specified the current directory is used.")
    String path = System.getProperty("user.dir");

    enum Action {
        all, dependencies, findcpe, querycve, sourceanalysis, translate, allcpe, printcpe, alllibraries
    }

    @CommandLine.Option(names = {"-a", "--action"}, description = "Action to take. Dependencies detects " +
            "the dependencies declared. Findcpe finds the corresponding cpe for each library, querycve " +
            "queries cve-s from NVD database.")
    Action action = Action.all;

    enum Platform {
        carthage, cocoapods, swiftpm, all
    }

    @CommandLine.Option(names = {"-p", "--platform"}, description = "Package manager that should be analysed. Either " +
            "cocoapods, carthage, swiftpm or all (default). (optional)")
    Platform platform = Platform.all;

    @CommandLine.Option(arity = "1", names = {"-r", "--recursive"}, description = "Find package manager artifacts" +
            " recursevly in subfolders (default false).")
    boolean subFolders = false;

    @CommandLine.Option(names = {"-s", "--specificValue"}, description = "Spcify a specific value for " +
            "the selected action. For depenencies a specific manifest file can be provided, for dindcpe" +
            " a specific library name can be provided and for querycve a specific cpe string can be provided.")
    String specificValue;

    @CommandLine.Option(arity = "1", names = {"-d", "--directDependencies"}, description = "Analyse only " +
            "direct dependencies.")
    boolean onlyDirectDependencies = true;

    @CommandLine.Option(arity = "1", names = {"-c", "--cpeOnlyFromFile"}, description = "Only query cpe-s " +
            "from cpes file.")
    boolean cpeOnlyFromFile = true;

    @CommandLine.Option(arity = "1", names = {"-f", "--findVulnerableComponentUsage"}, description = "Match vulnerable " +
            "dependency name in file.")
    boolean findVulnerableDependecyNameUsage = false;

    enum Level {
        debug, info, error, none
    }

    @CommandLine.Option(names = {"-l", "--logLevel"}, description = "Set logging level, default is info. " +
            "Options: debug, info, error and none.")
    Level logLevel = Level.info;

    @Override
    public void run() {
        try {
            Settings settings = new Settings();
            switch (action){
                case all:
                    DependencyChecker allCaseAnalyser = new DependencyChecker(settings);
                    allCaseAnalyser.cpeOnlyFromFile = cpeOnlyFromFile;
                    allCaseAnalyser.onlyDirectDependencies = onlyDirectDependencies;

                    try {
                        Map<Library, CVEData> vulnerableVersionsUsed = allCaseAnalyser.analyseFolder(path);
                        for (Map.Entry<Library, CVEData> entry : vulnerableVersionsUsed.entrySet()) {
                            Library library = entry.getKey();
                            CVEData cveData = entry.getValue();

                            String subTarget = library.subtarget != null ? " - " + library.subtarget : "";
                            String module = library.module != null ? " (" + library.module + ")" : "";

                            LoggerHelper.log(LogLevel.INFO, "Library: " + library.name + " - " + library.versionString + subTarget + module);
                            if (cveData.cve != null && cveData.cve.description != null) {
                                LoggerHelper.log(LogLevel.INFO,"  --  description: " + cveData.cve.description);
                            }
                            if (this.findVulnerableDependecyNameUsage){
                                DependencyAnalyser.searchInSwiftFiles(this.path, entry.getKey().module);
                            }
                        }
                    } catch (MalformedURLException e) {
                        throw new RuntimeException(e);
                    } catch (URISyntaxException e) {
                        throw new RuntimeException(e);
                    }

                    break;
                case sourceanalysis:
                    DependencyChecker sourceAnalyserCase = new DependencyChecker(settings);
                    sourceAnalyserCase.onlyDirectDependencies = onlyDirectDependencies;
                    sourceAnalyserCase.cpeOnlyFromFile = cpeOnlyFromFile;

                    try {
                        Map<Library, CVEData> vurnableVersionUsed = sourceAnalyserCase.analyseFolder(path);
                        SourceAnalyser sourceAnalyser = new SourceAnalyser();
                        List<FileLocation> locations = sourceAnalyser.analyseProject(path, vurnableVersionUsed);

                        for (FileLocation location : locations) {
                            LoggerHelper.log(LogLevel.INFO, location.path + ":" + location.line + ":8: warning: " + location.warning + " (vulnerable version)");
                        }
                    } catch (MalformedURLException e) {
                        throw new RuntimeException(e);
                    } catch (URISyntaxException e) {
                        throw new RuntimeException(e);
                    }

                    break;
                case dependencies:
                    DependencyAnalyser dependenciesAnalyser = new DependencyAnalyser(settings);

                    dependenciesAnalyser.onlyDirectDependencies = onlyDirectDependencies;

                    try {
                        List<Library> libraries = dependenciesAnalyser.analyseApp(path);
                        for (Library library : libraries) {
                            StringBuilder dataString = new StringBuilder();

                            String subTarget = library.subtarget != null ? " sub-target:" + library.subtarget : "";
                            String module = library.module != null ? " module:" + library.module : "";
                            String platform = library.platform != null ? " platform:" + library.platform + " " : "";

                            dataString.append(platform)
                                    .append("name:").append(library.name)
                                    .append(" version:").append(library.versionString)
                                    .append(subTarget)
                                    .append(module);

                            if (library.directDependency != null) {
                                if (library.directDependency) {
                                    LoggerHelper.log(LogLevel.INFO, dataString.toString());
                                } else {
                                    LoggerHelper.log(LogLevel.INFO, "Indirect " + dataString.toString());
                                }
                            } else {
                                LoggerHelper.log(LogLevel.INFO, dataString.toString());
                            }
                        }
                    } catch (MalformedURLException e) {
                        throw new RuntimeException(e);
                    } catch (URISyntaxException e) {
                        throw new RuntimeException(e);
                    }


                    break;
                case findcpe:
                    CPEFinder findCpeAnalyser = new CPEFinder(settings);
                    findCpeAnalyser.cpeOnlyFromFile = cpeOnlyFromFile;

                    if (specificValue != null) {
                        LoggerHelper.log(LogLevel.INFO, "For library name: " + specificValue);
                        String cpe = findCpeAnalyser.findCPEForLibrary(specificValue);
                        if (cpe != null) {
                            LoggerHelper.log(LogLevel.INFO, "found cpe: " + cpe);
                        } else {
                            LoggerHelper.log(LogLevel.INFO, "no found cpe");
                        }
                    } else {
                        LoggerHelper.log(LogLevel.INFO, "[!] Currently only analysis with specific value supported.");
                    }
                    break;

                case querycve:
                    VulnerabilityAnalyser analyser = new VulnerabilityAnalyser(settings);
                    if (specificValue != null) {
                        LoggerHelper.log(LogLevel.INFO, "Vulnerabilities for cpe: " + specificValue);
                        //TODO: check if cpe has correct format??

                        List<CVEData> cveList = analyser.queryVulnerabilitiesFor(specificValue);
                        LoggerHelper.log(LogLevel.INFO, "Found vulnerabilities: " + cveList);
                        for (CVEData cve : cveList) {
                            if (cve.cve != null && cve.cve.description != null) {
                                LoggerHelper.log(LogLevel.INFO, "Vulnerability: " + cve.cve.description);
                                if (cve.configuration != null) {
                                    List<CPEMatch> affectedVersions = cve.configuration.getAffectedVersions();
                                    for (CPEMatch version : affectedVersions) {
                                        LoggerHelper.log(LogLevel.INFO, "    cpe: " + version.cpeString);
                                        if (version.versionStartIncluding != null) {
                                            LoggerHelper.log(LogLevel.INFO, "    startincluding: " + version.versionStartIncluding);
                                        }
                                        if (version.versionStartExcluding != null) {
                                            LoggerHelper.log(LogLevel.INFO, "    startexcluding: " + version.versionStartExcluding);
                                        }
                                        if (version.versionEndIncluding != null) {
                                            LoggerHelper.log(LogLevel.INFO, "    endtincluding: " + version.versionEndIncluding);
                                        }
                                        if (version.versionEndExcluding != null) {
                                            LoggerHelper.log(LogLevel.INFO, "    endtexcluding: " + version.versionEndExcluding);
                                        }
                                    }
                                }
                            } else {
                                LoggerHelper.log(LogLevel.ERROR, "[!] No description");
                            }
                        }
                    } else {
                        LoggerHelper.log(LogLevel.INFO, "[!] Currently only analysis with specific value supported.");
                    }
                    break;

                case translate:
                    DependencyAnalyser translateAnalyser = new DependencyAnalyser(settings);
                    if (specificValue != null) {
                        String[] components = specificValue.split(",");
                        if (components.length == 2) {
                            String name = components[0].toLowerCase();
                            String version = components[1];
                            LoggerHelper.log(LogLevel.INFO, "name: " + name + ", version: " + version);

                            Tuple translation = translateAnalyser.translateLibraryVersion(name, version);
                            if (translation != null) {
                                LoggerHelper.log(LogLevel.INFO, "translation: " + translation.getValue(0).toString().toLowerCase() + ":" + (translation.getValue(2) != null ? translation.getValue(2) : components[1]));
                            } else {
                                LoggerHelper.log(LogLevel.INFO, "no translation");
                            }
                        } else {
                            LoggerHelper.log(LogLevel.ERROR, "[!] Specific value should be of form: name,version");
                        }
                    } else {
                        LoggerHelper.log(LogLevel.ERROR, "[!] Currently only analysis with specific value supported.");
                    }
                    break;

                case allcpe:
                    CPEFinder allCpeAnalyser = new CPEFinder(settings);
                    allCpeAnalyser.generateDictionaryWithAllCPEs();

                    LoggerHelper.log(LogLevel.INFO, "Found " + allCpeAnalyser.cpeDictionary.dictionary.size() + " cpes in total");
                    break;

                case alllibraries:
                    DependencyChecker allLibrariesAnalyser = new DependencyChecker(settings);
                    allLibrariesAnalyser.cpeOnlyFromFile = cpeOnlyFromFile;

                    Map<String, Pair<String, List<CVEData>>> results;

                    if (specificValue != null) {
                        results = allLibrariesAnalyser.analyseLibraries(specificValue);
                    } else {
                        results = allLibrariesAnalyser.analyseAllLibraries();
                    }

                    for (String library : results.keySet()) {
                        LoggerHelper.log(LogLevel.INFO, library + ": cpe: " + results.get(library).getValue0() + ", vulnerabilities " + results.get(library).getValue1().size());
                    }
                    break;

                case printcpe:
                    CPEFinder printCpeAnalyser = new CPEFinder(settings);

                    if (cpeOnlyFromFile) {
                        LoggerHelper.log(LogLevel.DEBUG, "[i] Cpe only from file.");
                    } else {
                        LoggerHelper.log(LogLevel.DEBUG, "[*] Generating new cpe dictionary.");
                        printCpeAnalyser.generateDictionaryWithAllCPEs();
                    }

                    for (Map.Entry<String, CPE> entry : printCpeAnalyser.cpeDictionary.dictionary.entrySet()) {
                        String libraryName = entry.getKey();
                        CPE cpe = entry.getValue();
                        LoggerHelper.log(LogLevel.INFO, libraryName + " " + (cpe != null ? cpe.value : "--"));
                    }
                    break;

            }
        } catch (MalformedURLException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}

@Command(name = "toolSettings")
class ToolSettings implements Runnable {

    enum Action { get, set, displayall }

    @CommandLine.Option(names = {"-a", "--action"}, defaultValue = "displayall", description = "Action to take: get, set or displayall.")
    Action action;

    enum Property { homeFolder, specTimeInterval, cpeTimeInterval, vulnerabilityTimeInterval, specDirectory }

    @CommandLine.Option(names = {"-p", "--property"}, description = "which property to set or get: homeFolder, specTimeInterval, cpeTimeInterval, vulnerabilityTimeInterval, specDirectory")
    Property property;

    @CommandLine.Option(names = {"-v", "--value"}, description = "which value to set")
    String value;


    @Override
    public void run() {
        try {
            SettingsController settingsController = new SettingsController();
            System.out.println("Settings");
            switch (action) {
                case get:
                    if (property != null) {
                        switch (property) {
                            case homeFolder:
                                System.out.println(settingsController.settings.homeFolder);
                                break;
                            case specDirectory:
                                System.out.println(settingsController.settings.specDirectory);
                                break;
                            case specTimeInterval:
                                System.out.println(settingsController.settings.specTranslationTimeInterval);
                                break;
                            case cpeTimeInterval:
                                System.out.println(settingsController.settings.cpeTimeInterval);
                                break;
                            case vulnerabilityTimeInterval:
                                System.out.println(settingsController.settings.vulnerabilityTimeInterval);
                                break;
                        }
                    } else {
                        System.out.println("Property not defined.");
                    }
                    break;
                case set:
                    if (property != null && value != null) {
                        switch (property) {
                            case homeFolder:
                                java.io.File url = new java.io.File(value);
                                settingsController.folder = url.toURI().toURL();
                                settingsController.settings.homeFolder = url.toURI().toURL();
                                settingsController.changed = true;
                                break;
                            case specDirectory:
                                java.io.File specUrl = new java.io.File(value);
                                settingsController.settings.specDirectory = specUrl.toURI().toURL();
                                settingsController.changed = true;
                                break;
                            case specTimeInterval:
                                try {
                                    settingsController.settings.specTranslationTimeInterval = Long.parseLong(value);
                                    settingsController.changed = true;
                                } catch (NumberFormatException e) {
                                    System.out.println("Value " + value + " not a time interval.");
                                }
                                break;
                            case cpeTimeInterval:
                                try {
                                    settingsController.settings.cpeTimeInterval = Long.parseLong(value);
                                    settingsController.changed = true;
                                } catch (NumberFormatException e) {
                                    System.out.println("Value " + value + " not a time interval.");
                                }
                                break;
                            case vulnerabilityTimeInterval:
                                try {
                                    settingsController.settings.vulnerabilityTimeInterval = Long.parseLong(value);
                                    settingsController.changed = true;
                                } catch (NumberFormatException e) {
                                    System.out.println("Value " + value + " not a time interval.");
                                }
                                break;
                        }
                    } else {
                        System.out.println("Property or value not defined.");
                    }
                    break;
                case displayall:
                    System.out.println("Homefolder: " + settingsController.settings.homeFolder);
                    System.out.println("TimeInterval for spec analysis: " + settingsController.settings.specTranslationTimeInterval);
                    System.out.println("TimeInterval for cpe analysis: " + settingsController.settings.cpeTimeInterval);
                    System.out.println("TimeInterval for vulnerability analysis: " + settingsController.settings.vulnerabilityTimeInterval);
                    break;
            }


        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }
}
