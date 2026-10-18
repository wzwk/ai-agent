package dev.example.productai.search;

import dev.example.productai.config.ProjectsConfigLoader;
import dev.example.productai.config.ProjectsProperties;
import dev.example.productai.config.SearchProperties;
import dev.example.productai.model.ProjectInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JavaSymbolIndexTest {

    @TempDir
    Path temp;

    @Test
    void findsJavaTypeBySimpleOrQualifiedNameAndHonorsProjectScope() throws Exception {
        Path a = Files.createDirectories(temp.resolve("service-a/src/main/java/demo"));
        Path b = Files.createDirectories(temp.resolve("service-b/src/main/java/demo"));
        Files.writeString(a.resolve("OrderService.java"), "package demo; class OrderService {}");
        Files.writeString(b.resolve("OrderService.java"), "package demo; class OrderService {}");
        Files.createDirectories(temp.resolve("service-a/target/generated"));
        Files.writeString(temp.resolve("service-a/target/generated/Ignored.java"), "class Ignored {}");

        ProjectsConfigLoader loader = mock(ProjectsConfigLoader.class);
        when(loader.getProjects()).thenReturn(List.of(
                ProjectInfo.builder().name("service-a").path("service-a").build(),
                ProjectInfo.builder().name("service-b").path("service-b").build()));
        ProjectsProperties properties = new ProjectsProperties();
        properties.setCodeBase(temp.toString());
        SearchProperties search = new SearchProperties();
        search.setSymbolIndexTtlMs(60_000);
        JavaSymbolIndex index = new JavaSymbolIndex(loader, properties, search);

        assertThat(index.find("demo.OrderService", List.of())).hasSize(2);
        assertThat(index.find("OrderService", List.of("service-b")))
                .extracting(JavaSymbolIndex.Hit::project).containsExactly("service-b");
        assertThat(index.find("Ignored", List.of())).isEmpty();
    }
}
