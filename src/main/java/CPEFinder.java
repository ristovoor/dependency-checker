import com.google.gson.*;

import java.lang.reflect.Type;
import java.util.zip.GZIPInputStream;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;


public class CPEFinder implements AutoCloseable{
    public CPEDictionary cpeDictionary;
    private URL url;
    private URL folder; // Changed to URL
    private boolean changed = false;
    private Settings settings;
    private Path cpePath;
    public boolean cpeOnlyFromFile = false;

    public CPEFinder(Settings settings) throws URISyntaxException {
        this.settings = settings;
        this.folder = settings.homeFolder;

        try {
            this.url = this.folder.toURI().resolve("cpes.json").toURL(); // Convert to URL
            byte[] data = Files.readAllBytes(Paths.get(this.url.toURI()));
            Gson gson = new GsonBuilder().registerTypeAdapter(Date.class, new DateDeserializer()).create();
            String stringData = new String(data);
            CPEDictionary decoded = gson.fromJson(stringData, CPEDictionary.class);

            /*
            Gson gson = new Gson();
            String stringData = new String(data);
            Translations decoded = gson.fromJson(stringData, Translations.class);

            JsonObject jsonObject = gson.fromJson(new FileReader(String.valueOf(Paths.get(url.toURI()))), JsonObject.class);
            double epochDate = jsonObject.get("lastUpdated").getAsDouble();

            decoded.date = new Date( (long) (epochDate * 1000)); // Probably wrong conversion
            translations = decoded;
             */
            this.cpeDictionary = decoded != null ? decoded : new CPEDictionary(new Date());
        } catch (IOException | URISyntaxException e) {
            this.cpeDictionary = new CPEDictionary(new Date());
        }

        this.cpePath = Paths.get(this.folder.toURI()).resolve("official-cpe-dictionary_v2.3.xml"); // Convert to Path

        if (!cpeOnlyFromFile) {
            if (!checkCPEDatafile()) {
                downloadCPEDataFile();
            }

            if (shouldUpdate()) {
                update();
            }
        }
    }
    public boolean checkCPEDatafile() {
        LoggerHelper.log(LogLevel.DEBUG, "[*] Checking cpe data path: " + this.cpePath.toString());
        boolean pathExists = Files.exists(this.cpePath);
        LoggerHelper.log(LogLevel.DEBUG, "[i] Cpe data path exists: " + pathExists);

        return pathExists;
    }

    public void downloadCPEDataFile() {
        LoggerHelper.log(LogLevel.INFO, "[*] Downloading new CPE data file...");
        String downloadPath = "https://nvd.nist.gov/feeds/xml/cpe/dictionary/official-cpe-dictionary_v2.3.xml.gz";
        try {
            URL url = new URL(downloadPath);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream inputStream = connection.getInputStream();
                File gzipFile = new File("path_to_cpe_file.gz"); // Set your desired path here
                FileOutputStream outputStream = new FileOutputStream(gzipFile);
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.close();

                byte[] decompressedData = decompress(gzipFile);
                File cpeFile = new File(this.cpePath.toString()); // Set your desired path here
                FileOutputStream cpeOutputStream = new FileOutputStream(cpeFile);
                cpeOutputStream.write(decompressedData);
                cpeOutputStream.close();
            } else {
                LoggerHelper.log(LogLevel.ERROR, "[!] Failed to download the CPE data file. HTTP response code: " + responseCode);
            }
        } catch (IOException e) {
            LoggerHelper.log(LogLevel.ERROR, "[!] Downloading official cpe dictionary failed: " + e.getMessage());
        }
    }

    private byte[] decompress(File gzipFile) throws IOException {
        try (FileInputStream fileInputStream = new FileInputStream(gzipFile);
             GZIPInputStream gzipInputStream = new GZIPInputStream(fileInputStream);
             ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = gzipInputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead);
            }
            return byteArrayOutputStream.toByteArray();
        }
    }

    public boolean shouldUpdate() {
        LoggerHelper.log(LogLevel.INFO, "[i] Last updated cpe dictionary: " + cpeDictionary.lastUpdated);

        if (settings.cpeTimeInterval != null) {
            long timeInterval = settings.cpeTimeInterval;

            // check if time since last updated is larger than the allowed time interval for updates
            if ((new Date().getTime() - cpeDictionary.lastUpdated.getTime()) > timeInterval) {
                LoggerHelper.log(LogLevel.INFO, "[i] Will update cpe dictionary");
                return true;
            }
        }

        LoggerHelper.log(LogLevel.INFO, "[i] No update for cpe dictionary");
        return false;
    }

    @Override
    public void close() {
        if (changed) {
            save();
        }
    }

    public void update() {
        LoggerHelper.log(LogLevel.DEBUG, "[*] Updating cpe dictionary");
        updateCPEDataFile();
        Map<String, CPE> updatedCPEs = new HashMap<>();

        for (Map.Entry<String, CPE> entry : cpeDictionary.dictionary.entrySet()) {
            CPE cpe = entry.getValue();
            if (cpe.value == null) {
                // ignore, these will be removed from cpes so that they can be updated
            } else {
                updatedCPEs.put(entry.getKey(), cpe);
            }
        }
        cpeDictionary.lastUpdated = new Date();
        cpeDictionary.dictionary = updatedCPEs;

        changed = true;
    }
    public void updateCPEDataFile() {
        LoggerHelper.log(LogLevel.DEBUG, "[*] Updating CPE data file");
        try {
            Files.deleteIfExists(cpePath);
        } catch (IOException e) {
            LoggerHelper.log(LogLevel.ERROR, "[!] Removing cpe dictionary failed: " + e.getMessage());
        }
        downloadCPEDataFile();
    }

    public String findCPEForLibrary(String name) {
        LoggerHelper.log(LogLevel.DEBUG, "[*] Finding CPE for library " + name);
        if (cpeDictionary.dictionary.containsKey(name)) {
            LoggerHelper.log(LogLevel.DEBUG, "[i] Found existing CPE value: " + cpeDictionary.dictionary.get(name).value);
            return cpeDictionary.dictionary.get(name).value;
        }

        if (cpeOnlyFromFile) {
            return null;
        }

        if (name.contains("/")) {
            String cpePath = this.cpePath.toString();

            if (Files.exists(Paths.get(cpePath))) {
                LoggerHelper.log(LogLevel.DEBUG, "[*] Searching for cpe for title: " + name);
                LoggerHelper.log(LogLevel.DEBUG, "[*] Querying from file: " + cpePath + " ...");

                try {
                    String cpeData = new String(Files.readAllBytes(Paths.get(cpePath)));
                    String[] lines = cpeData.split("\\r?\\n");

                    boolean itemFound = false;
                    int lineCount = 0;
                    for (String line : lines) {
                        line = line.toLowerCase();

                        if (itemFound) {
                            lineCount++;
                        }

                        if (line.contains(name)) {
                            itemFound = true;
                            lineCount = 0;
                        }

                        if (itemFound) {
                            if (line.contains("</cpe-item>")) {
                                itemFound = false;
                                lineCount = 0;
                            }

                            if (line.contains("<cpe-23:cpe23-item name=\"")) {
                                String[] components = line.split("<cpe-23:cpe23-item name=\"");
                                if (components.length > 0) {
                                    String value = components[components.length - 1];
                                    value = value.replace("\"/>", "");

                                    String[] splitValues = value.split(":");
                                    splitValues[5] = "*";
                                    String cleanedCpe = String.join(":", splitValues);
                                    LoggerHelper.log(LogLevel.DEBUG, "[i] cleaned cpe: " + cleanedCpe);

                                    cpeDictionary.dictionary.put(name, new CPE(cleanedCpe));
                                    changed = true;

                                    return cleanedCpe;
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    LoggerHelper.log(LogLevel.ERROR, "[!] Could not read cpe file at " + cpePath);
                }
            } else {
                LoggerHelper.log(LogLevel.ERROR, "[!] Cpe dictionary not found!");
            }
        } else {
            LoggerHelper.log(LogLevel.DEBUG, "[i] Name does not contain \"/\", ignore");
        }

        cpeDictionary.dictionary.put(name, new CPE(null));
        changed = true;

        return null;
    }

    public void generateDictionaryWithAllCPEs() { // VÕIKSID MUUTA XML LUGEMIST
        String cpePath = this.cpePath.toString();
        int foundCount = 0;

        if (java.nio.file.Files.exists(java.nio.file.Paths.get(cpePath))) {
            LoggerHelper.log(LogLevel.DEBUG, "[*] Querying from file: " + cpePath + " ...");

            try (BufferedReader reader = new BufferedReader(new FileReader(cpePath))) {
                String line;
                boolean itemFound = false;
                int lineCount = 0;
                String name = "";

                while ((line = reader.readLine()) != null) {
                    line = line.toLowerCase();

                    if (itemFound) {
                        lineCount++;
                    }

                    if (line.contains("<reference href=") && !itemFound) {
                        LoggerHelper.log(LogLevel.DEBUG, "[i] Found reference: " + line);

                        line = line.trim();
                        line = line.replace("<reference href=\"", "");
                        String[] components = line.split(">");
                        String href = components[0].replace("\"", "");

                        String foundName = getNameFromUrl(href);
                        if (foundName != null) {
                            LoggerHelper.log(LogLevel.DEBUG, "[i] Found name: " + foundName);
                            name = foundName;
                            itemFound = true;
                            lineCount = 0;
                        } else {
                            LoggerHelper.log(LogLevel.DEBUG, "[i] No name");
                        }
                    }

                    if (itemFound) {
                        if (line.contains("</cpe-item>")) {
                            itemFound = false;
                            lineCount = 0;
                            name = "";
                        }

                        if (line.contains("<cpe-23:cpe23-item name=\"")) {
                            String[] components = line.split("<cpe-23:cpe23-item name=\"");
                            if (components.length > 0) {
                                String value = components[components.length - 1].replace("\"/>", "");
                                String[] splitValues = value.split(":");
                                splitValues[5] = "*";
                                String cleanedCpe = String.join(":", splitValues);
                                LoggerHelper.log(LogLevel.DEBUG, "[i] cleaned cpe: " + cleanedCpe);

                                this.cpeDictionary.dictionary.put(name, new CPE(cleanedCpe));
                                this.changed = true;
                                foundCount++;
                                LoggerHelper.log(LogLevel.DEBUG, "[i] Total number of cpes found: " + foundCount);

                                if (foundCount % 100 == 0) {
                                    save();
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                LoggerHelper.log(LogLevel.ERROR, "[!] Could not read cpe file at " + cpePath);
            }
        } else {
            LoggerHelper.log(LogLevel.ERROR, "[!] Cpe dictionary not found!");
        }
    }

    public static String getNameFromUrl(String url) {
        url = url.toLowerCase();
        String platform = "";
        if (url.contains("github.com")) {
            platform = "github.com";
        } else if (url.contains("bitbucket.org")) {
            platform = "bitbucket.org";
        } else {
            return null;
        }

        String[] components = url.split(platform);
        if (components.length < 2) {
            return null;
        }

        String tail = components[1];
        tail = tail.substring(1); // Drop : or / in the beginning

        String[] parts = tail.split("/");
        if (parts.length < 2) {
            return null;
        }

        String name = parts[0] + "/" + parts[1];
        return name;
    }



    public void save() {
        checkFolder();

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(this.cpeDictionary);

        try (FileWriter fileWriter = new FileWriter(String.valueOf(url))) {
            fileWriter.write(json);
        } catch (IOException e) {
            LoggerHelper.log(LogLevel.ERROR, "[!] Could not save cpes");
        }
    }
    public void checkFolder() {
        File folderFile = new File(folder.getPath());
        if (!folderFile.exists()) {
            if (!folderFile.mkdirs()) {
                LoggerHelper.log(LogLevel.ERROR, "[!] Could not create folder: " + folder);
            }
        }
    }
    class DateDeserializer implements JsonDeserializer<Date> {
        @Override
        public Date deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            // Parse Unix timestamp from JSON string
            String dateString = json.getAsString();

            // Convert Unix timestamp string to long
            double unixTimestamp = Double.parseDouble(dateString);

            // Convert Unix timestamp to Date object
            return new Date((long) (unixTimestamp * 1000)); // Multiply by 1000 to convert seconds to milliseconds
        }
    }
}
class CPE {
    public String value;

    public CPE(String value) {
        this.value = value;
    }
}
class CPEDictionary {
    public Date lastUpdated;
    public Map<String, CPE> dictionary;

    public CPEDictionary(Date lastUpdated) {
        this.lastUpdated = lastUpdated;
        this.dictionary = new HashMap<>();
    }
}
