package io.casehub.langchain4j.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Every published module must be importable through the BOM. */
class BomCoverageTest {

    private static final Path ROOT = Path.of("..");

    private static List<String> matches(Path file, String regex) throws IOException {
        return matches(Files.readString(file), regex);
    }

    private static List<String> matches(String text, String regex) {
        List<String> found = new ArrayList<>();
        Matcher matcher = Pattern.compile(regex).matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    @Test
    void everyPublishedModuleHasABomEntry() throws IOException {
        List<String> modules = matches(ROOT.resolve("pom.xml"), "<module>([^<]+)</module>");
        modules.removeAll(List.of("bom", "examples"));
        assertThat(modules).isNotEmpty();

        String bom = Files.readString(ROOT.resolve("bom/pom.xml"));
        String managed = bom.substring(bom.indexOf("<dependencyManagement>"));
        List<String> bomArtifacts = matches(
                managed, "<groupId>io\\.casehub\\.langchain4j</groupId>\\s*<artifactId>([^<]+)</artifactId>");

        List<String> expected = new ArrayList<>();
        for (String module : modules) {
            expected.add(matches(ROOT.resolve(module).resolve("pom.xml"),
                    "</parent>\\s*<artifactId>([^<]+)</artifactId>").get(0));
        }
        assertThat(bomArtifacts).containsExactlyInAnyOrderElementsOf(expected);
    }
}
