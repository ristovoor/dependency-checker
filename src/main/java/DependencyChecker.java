import org.javatuples.Pair;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.*;

class DependencyChecker {
    private final Settings settings;
    public boolean onlyDirectDependencies = false;
    public boolean cpeOnlyFromFile = false;

    public DependencyChecker(Settings settings) {
        this.settings = settings;
    }

    public Map<String, Pair<String, List<CVEData>>> analyseAllLibraries() throws URISyntaxException {
        LoggerHelper.log(LogLevel.INFO, "[*] Analysing all libraries.");

        Map<String, Pair<String, List<CVEData>>> results = new HashMap<>();

        CPEFinder cpeFinder = new CPEFinder(settings);
        VulnerabilityAnalyser vulnerabilityAnalyser = new VulnerabilityAnalyser(settings);

        for (Map.Entry<String, CPE> entry : cpeFinder.cpeDictionary.dictionary.entrySet()) {
            String libraryName = entry.getKey();
            CPE cpe = entry.getValue();

            if (cpe != null) {
                List<CVEData> vulnerabilities = vulnerabilityAnalyser.queryVulnerabilitiesFor(cpe.value);
                LoggerHelper.log(LogLevel.DEBUG, "[i] Found " + vulnerabilities.size() + " vulnerabilities.");
                results.put(libraryName, new Pair<>(cpe.value, vulnerabilities));
            }
        }

        return results;
    }
    public Map<String, Pair<String, List<CVEData>>> analyseLibraries(String filePath) {
        LoggerHelper.log(LogLevel.DEBUG, "[*] Analysing filePath: " + filePath + " ...");

        Map<String, Pair<String, List<CVEData>>> results = new HashMap<>();

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            CPEFinder cpeFinder = new CPEFinder(settings);
            cpeFinder.cpeOnlyFromFile = cpeOnlyFromFile;
            VulnerabilityAnalyser vulnerabilityAnalyser = new VulnerabilityAnalyser(settings);
            while ((line = br.readLine()) != null) {
                String libraryName = line.trim();
                if (libraryName.contains("/")) {
                    LoggerHelper.log(LogLevel.DEBUG, "[*] Analysing: " + libraryName + "...");

                    if (results.containsKey(libraryName)) {
                        LoggerHelper.log(LogLevel.DEBUG, "[i] Library already analysed, ignore.");
                        continue;
                    }

                    String cpe = cpeFinder.findCPEForLibrary(libraryName);
                    if (cpe != null) {
                        LoggerHelper.log(LogLevel.DEBUG, "[i] Found cpe: " + cpe);
                        List<CVEData> vulnerabilities = vulnerabilityAnalyser.queryVulnerabilitiesFor(cpe);
                        LoggerHelper.log(LogLevel.DEBUG, "[i] Found " + vulnerabilities.size() + " vulnerabilities.");
                        results.put(libraryName, new Pair<>(cpe, vulnerabilities));
                    } else {
                        LoggerHelper.log(LogLevel.DEBUG, "[i] No cpe found");
                    }
                } else {
                    LoggerHelper.log(LogLevel.DEBUG, "[i] Ignoring line: " + libraryName);
                }
            }
        } catch (IOException e) {
            LoggerHelper.log(LogLevel.ERROR, "[!] Could not read file: " + filePath);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        return results;
    }

    public Map<Library, CVEData> analyseFolder(String path) throws MalformedURLException, URISyntaxException {
        LoggerHelper.log(LogLevel.INFO, "[*] Analysing folder: " + path + " ...");

        // find all dependencies
        DependencyAnalyser analyser = new DependencyAnalyser(settings);
        analyser.onlyDirectDependencies = this.onlyDirectDependencies;

        List<Library> libraries = analyser.analyseApp(path);
        LoggerHelper.log(LogLevel.INFO, "[i] Found " + libraries.size() + " dependencies.");
        LoggerHelper.log(LogLevel.DEBUG, "[i] Found dependencies: ");
        for (Library library : libraries) {
            String subTarget = "";
            if (library.subtarget != null) {
                subTarget = " - " + library.subtarget;
            }

            String dependencyType = (library.directDependency != null && library.directDependency) ? "Direct" : "Indirect";
            LoggerHelper.log(LogLevel.DEBUG, "[i] " + dependencyType + ": " + library.name + " " + library.versionString + subTarget);
        }

        // find matching cpes
        LoggerHelper.log(LogLevel.INFO, "[*] Finding matching cpe values ...");

        List<AnalysedLibrary> analysedLibraries = new ArrayList<>();
        libraryLoop:
        for (Library library : libraries) {
            LoggerHelper.log(LogLevel.DEBUG, "[*] Trying to match library: " + library.name + ", module: " + (library.module != null ? library.module : "") + ", subtarget: " + (library.subtarget != null ? library.subtarget : ""));
            for (AnalysedLibrary analysedLibrary : analysedLibraries) {
                if (analysedLibrary.name.equals(library.name)) {
                    analysedLibrary.versionsUsed.add(library);
                    continue libraryLoop;
                }
            }
            AnalysedLibrary newAnalysedLibrary = new AnalysedLibrary(library.name);
            newAnalysedLibrary.versionsUsed.add(library);
            analysedLibraries.add(newAnalysedLibrary);
        }

        CPEFinder cpeFinder = new CPEFinder(settings);

        int count = 0;
        for (AnalysedLibrary analysedLibrary : analysedLibraries) {
            String name = analysedLibrary.name;
            String  cpe = cpeFinder.findCPEForLibrary(name);
            if (cpe != null) {
                count++;
                analysedLibrary.cpe = cpe;
                LoggerHelper.log(LogLevel.DEBUG, "[i] For library " + name + " found cpe: " + cpe);
            }
        }
        LoggerHelper.log(LogLevel.INFO, "[i] Found " + count + " matching cpe values.");

        LoggerHelper.log(LogLevel.INFO, "[*] Querying vulnerability for each found cpe value ...");
        // query vulnerabilities for each found cpe
        count = 0;

        VulnerabilityAnalyser vulnerabilityAnalyser = new VulnerabilityAnalyser(settings);

        for (AnalysedLibrary analysedLibrary : analysedLibraries) {
            String cpe = analysedLibrary.cpe;
            if (cpe != null) {
                List<CVEData> cveData = vulnerabilityAnalyser.queryVulnerabilitiesFor(cpe);
                count += cveData.size();
                analysedLibrary.vulnerabilities = cveData;
                LoggerHelper.log(LogLevel.DEBUG, "[i] For library: " + analysedLibrary.name + " found " + cveData.size() + " vulnerabilities");
            }
        }
        LoggerHelper.log(LogLevel.INFO, "[i] Found " + count + " possible vulnerabilities in used libraries.");

        // check if any of the used library versions are vulnerable
        LoggerHelper.log(LogLevel.INFO, "[*] Matching vulnerable library versions to used library versions ...");

        List<Pair<Library, CVEData>> vulnerableVersionsUsed = new ArrayList<>();

        for (AnalysedLibrary library : analysedLibraries) {
            LoggerHelper.log(LogLevel.DEBUG, "[i] For library " + library.name + " following vulnerabilities were found:");
            List<Pair<Library, CVEData>> versions = library.vulnerableVersionsUsed();
            vulnerableVersionsUsed.addAll(versions);
        }

        LoggerHelper.log(LogLevel.INFO, "[i] In total " + vulnerableVersionsUsed.size() + " used vulnerable library versions found.");

        Map<Library, CVEData> result = new HashMap<>();
        for (Pair<Library, CVEData> pair : vulnerableVersionsUsed) {
            Library library = pair.getValue0();
            CVEData cveData = pair.getValue1();
            result.put(library, cveData);
        }

        return result;
    }


}

class AnalysedLibrary {
    public final String name;
    public List<Library> versionsUsed = new ArrayList<>();
    public String cpe;
    public List<CVEData> vulnerabilities = new ArrayList<>();

    public AnalysedLibrary(String name) {
        this.name = name;
    }
    public List<Pair<Library, CVEData>> vulnerableVersionsUsed() {
        List<Pair<Library, CVEData>> vulnerableVersions = new ArrayList<>();

        for (CVEData vulnerability : vulnerabilities) {
            LoggerHelper.log(LogLevel.DEBUG, "[*] Matching libraries to vulnerability: " + (vulnerability.cve != null ? vulnerability.cve.description : ""));
            List<CPEMatch> versions = vulnerability.configuration != null ? vulnerability.configuration.getAffectedVersions() : null;
            if (versions != null) {
                libraryLoop: for (Library library : versionsUsed) {
                    LoggerHelper.log(LogLevel.DEBUG, "[*] Matching to library: " + library.name);
                    for (CPEMatch version : versions) {
                        LoggerHelper.log(LogLevel.DEBUG, "[*] Matching vulnerable version: " + version.getVersionString());
                        Version libraryVersion = new Version(library.versionString);
                        LoggerHelper.log(LogLevel.DEBUG, "[*] Comparing to library version: " + library.versionString + ", " + version.getVersionString());
                        ComparableVersion libraryComparable = libraryVersion.comparableVersion;
                        if (libraryComparable != null) {
                            if (version.exactVersion != null) {
                                LoggerHelper.log(LogLevel.DEBUG, "[*] Comparing exact matches");
                                Version exactVersion = new Version(version.exactVersion);
                                ComparableVersion exactVersionComparable = exactVersion.comparableVersion;
                                if (exactVersionComparable != null) {
                                    if (libraryComparable.equals(exactVersionComparable)) {
                                        LoggerHelper.log(LogLevel.DEBUG, "[i] Is a match");
                                        vulnerableVersions.add(new Pair<>(library, vulnerability));
                                        continue libraryLoop;
                                    } else {
                                        LoggerHelper.log(LogLevel.DEBUG, "[i] Is not a match ");
                                        continue;
                                    }
                                }
                            }

                            if (version.versionEndExcluding != null) {
                                LoggerHelper.log(LogLevel.DEBUG, "[*] Comparing End excluding: " + version.versionEndExcluding);
                                Version endExcludingVersion = new Version(version.versionEndExcluding);
                                ComparableVersion endExcludingComparable = endExcludingVersion.comparableVersion;
                                if (endExcludingComparable != null) {
                                    if (libraryComparable.compareTo(endExcludingComparable) >= 0) {
                                        LoggerHelper.log(LogLevel.DEBUG, "[i] Not a match");
                                        continue;
                                    }
                                } else {
                                    // TODO what do we do then? Currently will include it just in case
                                }
                            } else {
                                LoggerHelper.log(LogLevel.DEBUG, "[i] Not comparable " + version.versionEndExcluding);
                            }

                            if (version.versionEndIncluding != null) {
                                LoggerHelper.log(LogLevel.DEBUG, "[*] Comparing End including: " + version.versionEndIncluding);
                                Version endIncludingVersion = new Version(version.versionEndIncluding);
                                ComparableVersion endIncludingComparable = endIncludingVersion.comparableVersion;
                                if (endIncludingComparable != null) {
                                    if (libraryComparable.compareTo(endIncludingComparable) > 0) {
                                        LoggerHelper.log(LogLevel.DEBUG, "[i] Not a match.");
                                        continue;
                                    }
                                }
                            } else {
                                LoggerHelper.log(LogLevel.DEBUG, "[i] Not comparable " + version.versionEndIncluding);
                            }

                            if (version.versionStartExcluding != null) {
                                LoggerHelper.log(LogLevel.DEBUG, "[*] Comparing start excluding: " + version.versionStartExcluding);
                                Version startExcludingVersion = new Version(version.versionStartExcluding);
                                ComparableVersion startExcludingComparable = startExcludingVersion.comparableVersion;
                                if (startExcludingComparable != null) {
                                    if (libraryComparable.compareTo(startExcludingComparable) <= 0) {
                                        LoggerHelper.log(LogLevel.DEBUG, "[i] Not a match.");
                                        continue;
                                    }
                                }
                            } else {
                                LoggerHelper.log(LogLevel.DEBUG, "[i] Not comparable " + version.versionStartExcluding);
                            }

                            if (version.versionStartIncluding != null) {
                                LoggerHelper.log(LogLevel.DEBUG, "[*] Comparing start including: " + version.versionStartIncluding);
                                Version startIncludingVersion = new Version(version.versionStartIncluding);
                                ComparableVersion startIncludingComparable = startIncludingVersion.comparableVersion;
                                if (startIncludingComparable != null) {
                                    if (libraryComparable.compareTo(startIncludingComparable) < 0) {
                                        LoggerHelper.log(LogLevel.DEBUG, "Not a match");
                                        continue;
                                    }
                                }
                            } else {
                                LoggerHelper.log(LogLevel.DEBUG, "[i] Not comparable " + version.versionStartIncluding);
                            }

                        } else {
                            LoggerHelper.log(LogLevel.DEBUG, "[i] Not comparable");
                        }

                        LoggerHelper.log(LogLevel.DEBUG, "[i] Is a match");
                        vulnerableVersions.add(new Pair<>(library, vulnerability));
                        continue libraryLoop;
                    }
                }
            }
        }

        return vulnerableVersions;
    }
}

class Version {
    public final String versionString;
    public final ComparableVersion comparableVersion;

    public Version(String from) {
        this.versionString = from;

        String version = from;
        if (version.startsWith("v")) {
            version = version.substring(1);
        }

        String[] components = version.split("\\.");

        List<Integer> parts = new ArrayList<>();
        boolean incorrectValue = false;

        for (String component : components) {
            String stringValue = component;

            if (stringValue.endsWith("-beta")) {
                stringValue = stringValue.replace("-beta", "");
            }

            try {
                int intValue = Integer.parseInt(stringValue);
                parts.add(intValue);
            } catch (NumberFormatException e) {
                incorrectValue = true;
            }
        }

        if (!incorrectValue) {
            this.comparableVersion = new ComparableVersion(parts);
        } else {
            this.comparableVersion = null;
        }
    }
}
class ComparableVersion implements Comparable<ComparableVersion> {
    private final List<Integer> values;

    public ComparableVersion(List<Integer> values) {
        this.values = values;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ComparableVersion)) return false;
        ComparableVersion other = (ComparableVersion) obj;
        if (values.size() != other.values.size()) return false;
        for (int i = 0; i < values.size(); i++) {
            if (!values.get(i).equals(other.values.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int compareTo(ComparableVersion other) {
        LoggerHelper.log(LogLevel.INFO, "[*] Comparing: " + this.values + " < " + other.values);
        int total = Math.min(this.values.size(), other.values.size());
        for (int i = 0; i < total; i++) {
            if (this.values.get(i) > other.values.get(i)) {
                return 1;
            } else if (this.values.get(i) < other.values.get(i)) {
                return -1;
            }
        }
        if (this.equals(other)) {
            return 0;
        }
        return this.values.size() < other.values.size() ? -1 : 1;
    }
}