import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;

public class Helper {
    public static String shell(String path, String... args) {
        LoggerHelper.log(LogLevel.DEBUG, "[*] Running Helper.shell for path " + path + ", arguments: " + Arrays.toString(args));
        String output = shellOptional(path, args);

        if (output != null) {
            return output;
        }

        // Something failed, try again once!
        output = shellOptional(path, args);

        if (output != null) {
            LoggerHelper.log(LogLevel.DEBUG, "[i] Helper.shell output: " + output);
            return output;
        }

        LoggerHelper.log(LogLevel.DEBUG, "[i] Helper.shell did not return anything");
        return "";
    }

    private static String shellOptional(String path, String... args) {
        LoggerHelper.log(LogLevel.DEBUG, "[*] Running Helper.shellOptional for path " + path + ", arguments: " + Arrays.toString(args));

        try {
            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.command(args);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return output.toString();
            } else {
                return null;
            }
        } catch (IOException | InterruptedException e) {
            //e.printStackTrace();
            return null;
        }
    }
}
