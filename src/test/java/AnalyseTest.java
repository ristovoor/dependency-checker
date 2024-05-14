import com.sun.source.tree.AssertTree;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AnalyseTest {

    @Test
    public void testAnalyseAllCocoaPods() throws MalformedURLException, URISyntaxException {
        // Test case to analyse with dependencies action
        Settings settings = new Settings();
        DependencyChecker allCaseAnalyser = new DependencyChecker(settings);
        String path = System.getProperty("user.dir") + "/src/test/java/TestFiles/CocoaPodsProjectTest";
        Map<Library, CVEData> actualValue = allCaseAnalyser.analyseFolder(path);

        Map<Library, CVEData> expectedValue = new HashMap<>();
        assertEquals(actualValue, expectedValue);
    }

}