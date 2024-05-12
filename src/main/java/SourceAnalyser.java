import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class SourceAnalyser {


    public List<FileLocation> analyseProject(String path, Map<Library, CVEData> vulnerableLibraries) {
        LoggerHelper.log(LogLevel.INFO, "[*] Analysing project files in " + path + " ...");
        List<FileLocation> fileLocations = new ArrayList<>();

        File directory = new File(path);
        String[] files = directory.list();
        if (files != null) {
            for (String filename : files) {
                if (filename.endsWith(".swift") || filename.endsWith("Podfile.lock") || filename.endsWith("Package.resolved") || filename.endsWith("Cartfile.resolved")) {
                    String fullPath = path + "/" + filename;
                    LoggerHelper.log(LogLevel.DEBUG, "[i] Found related file: " + fullPath);

                    String detectedPlatform = null;
                    if (filename.endsWith("Podfile.lock")) {
                        detectedPlatform = "cocoapods";
                    } else if (filename.endsWith("Cartfile.resolved")) {
                        detectedPlatform = "carthage";
                    } else if (filename.endsWith("Package.resolved")) {
                        detectedPlatform = "swiftpm";
                    }

                    try {
                        File file = new File(fullPath);
                        Scanner scanner = new Scanner(file);
                        int count = 1;
                        while (scanner.hasNextLine()) {
                            String line = scanner.nextLine().trim();
                            if (filename.endsWith("Podfile.lock") && line.startsWith("DEPENDENCIES:")) {
                                break;
                            }

                            if (line.startsWith("import") || line.startsWith("-") || line.startsWith("\"package\":") || filename.endsWith("Cartfile.resolved")) {
                                String[] components = line.split(" ");
                                if (components.length >= 2) {
                                    String name = components[1].replaceAll("\"", "").replaceAll(",", "");

                                    LoggerHelper.log(LogLevel.DEBUG, "[i] Found import statement: " + name);
                                    for (Map.Entry<Library, CVEData> entry : vulnerableLibraries.entrySet()) {
                                        Library library = entry.getKey();
                                        CVEData cveData = entry.getValue();

                                        if (library.platform != null && detectedPlatform != null && !library.platform.equals(detectedPlatform)) {
                                            continue;
                                        }

                                        String libraryName = library.name.toLowerCase();
                                        if (library.module != null) {
                                            libraryName = library.module.toLowerCase();
                                        }

                                        if (library.subtarget != null) {
                                            libraryName = libraryName + "/" + library.subtarget;
                                        }

                                        LoggerHelper.log(LogLevel.DEBUG, "[*] Comparing to library: " + libraryName);

                                        if (libraryName.endsWith(name.toLowerCase())) {
                                            LoggerHelper.log(LogLevel.DEBUG, "[i] Found match");
                                            String warning = "vulnerable";
                                            if (cveData.cve != null && cveData.cve.description != null) {
                                                warning = cveData.cve.description;
                                            }
                                            FileLocation newLocation = new FileLocation(fullPath, count, warning);
                                            fileLocations.add(newLocation);
                                        }
                                    }
                                }
                            }
                            count++;
                        }
                        scanner.close();
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        return fileLocations;
    }
}
class FileLocation {
    public String path;
    public int line;
    public String warning;

    public FileLocation(String path, int line, String warning) {
        this.path = path;
        this.line = line;
        this.warning = warning;
    }
}